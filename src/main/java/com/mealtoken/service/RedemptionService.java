package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.*;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.PaymentRepository;
import com.mealtoken.notification.NotificationService;
import com.mealtoken.repository.*;
import com.mealtoken.util.Refs;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The only place tokens are spent or refunded.
 *
 * Safety model: one transaction per redemption; the customer's ACTIVE subscription row is locked
 * (SELECT ... FOR UPDATE) so concurrent scans serialise; the partial unique index on
 * (subscription, date, slot) WHERE status='SERVED' and CHECK constraints are the last line of defence.
 */
@Service
public class RedemptionService {
    private final HotelRepository hotels;
    private final MealSlotRepository slots;
    private final SubscriptionRepository subs;
    private final SubscriptionPlanRepository plans;
    private final MealAttendanceRepository attendance;
    private final TokenTransactionRepository ledger;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;
    private final EntityManager em;
    private final PaymentRepository payments;

    /** Tokens kept back while money is still due; at least one token always stays usable. */
    public static int heldTokens(java.math.BigDecimal due, int hold, int totalTokens) {
        return due.signum() > 0 ? Math.max(0, Math.min(hold, totalTokens - 1)) : 0;
    }

    public RedemptionService(HotelRepository hotels, MealSlotRepository slots, SubscriptionRepository subs,
                             SubscriptionPlanRepository plans, MealAttendanceRepository attendance,
                             TokenTransactionRepository ledger, UserRepository users, AuditService audit,
                             NotificationService notifications, Clock clock, EntityManager em, PaymentRepository payments) {
        this.hotels = hotels; this.slots = slots; this.subs = subs; this.plans = plans;
        this.attendance = attendance; this.ledger = ledger; this.users = users; this.audit = audit;
        this.notifications = notifications; this.clock = clock; this.em = em; this.payments = payments;
    }

    @Transactional
    public RedemptionResult redeem(RedeemCommand c) {
        String key = blankToNull(c.idempotencyKey());
        RedemptionResult replay = replayOf(key, c.customerId());
        if (replay != null) return replay;

        Hotel hotel = hotels.findById(c.hotelId()).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!"ACTIVE".equals(hotel.getStatus())) throw new BusinessException("error.hotel.inactive");

        User customer = users.findById(c.customerId()).orElseThrow(() -> new BusinessException("error.customer.notfound"));
        if (!"ACTIVE".equals(customer.getStatus())) throw new BusinessException("error.customer.notfound");

        MealSlot slot = slots.findByIdAndHotelId(c.mealSlotId(), hotel.getId())
                .filter(MealSlot::isActive).orElseThrow(() -> new BusinessException("error.slot.invalid"));

        ZoneId zone = ZoneId.of(hotel.getTimezone());
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone));
        LocalDate today = now.toLocalDate();
        if (hotel.isEnforceMealWindow() && !within(slot, now.toLocalTime()))
            throw new BusinessException("error.slot.window");

        // ---- serialise on the subscription row ----
        Subscription sub = subs.lockActive(c.customerId(), hotel.getId())
                .orElseThrow(() -> new BusinessException(diagnose(c.customerId(), hotel.getId())));

        // a concurrent twin of this request may have committed while we waited for the lock
        replay = replayOf(key, c.customerId());
        if (replay != null) return replay;

        if (sub.getStartDate() == null || today.isBefore(sub.getStartDate())) throw new BusinessException("error.sub.notstarted");
        if (today.isAfter(sub.getExpiryDate())) throw new BusinessException("error.sub.expired");
        if (sub.getRemainingTokens() <= 0) throw new BusinessException("error.sub.exhausted");
        java.math.BigDecimal due = sub.getTotalAmount().subtract(payments.sumPaid(sub.getId()));
        if (sub.getRemainingTokens() <= heldTokens(due, hotel.getTokenHoldCount(), sub.getTotalTokens()))
            throw new BusinessException("error.redeem.balance.due");

        SubscriptionPlan plan = plans.findById(sub.getPlanId()).orElseThrow(() -> new BusinessException("error.notfound"));
        if (attendance.countBySubscriptionIdAndAttendanceDateAndStatus(sub.getId(), today, "SERVED") >= plan.getMealsPerDay())
            throw new BusinessException("error.daily.limit");
        if (attendance.existsBySubscriptionIdAndAttendanceDateAndMealSlotIdAndStatus(sub.getId(), today, slot.getId(), "SERVED"))
            throw new BusinessException("error.slot.duplicate");

        // ---- all rules passed: record attendance, deduct, write ledger (one atomic unit) ----
        MealAttendance a = new MealAttendance();
        a.setCustomerId(c.customerId());
        a.setHotelId(hotel.getId());
        a.setSubscriptionId(sub.getId());
        a.setMealSlotId(slot.getId());
        a.setAttendanceDate(today);
        a.setRedeemedAt(clock.instant());
        a.setReferenceNumber(Refs.next("MA"));
        a.setIdempotencyKey(key);
        a.setCreatedBy(c.actorUserId());
        a = attendance.saveAndFlush(a);

        int before = sub.getRemainingTokens();
        sub.setUsedTokens(sub.getUsedTokens() + 1);
        sub.setRemainingTokens(before - 1);
        if (sub.getRemainingTokens() == 0) sub.setStatus("EXHAUSTED");
        subs.saveAndFlush(sub);

        ledger.save(entry(sub.getId(), "DEBIT", 1, before, before - 1, a.getReferenceNumber(),
                "Meal: " + slot.getName(), c.actorUserId()));

        audit.log(c.actorUserId(), hotel.getId(), "MEAL_REDEEMED", "MealAttendance", a.getId(),
                "mode=" + c.mode() + " ref=" + a.getReferenceNumber() + " slot=" + slot.getCode());
        notifications.notify(c.customerId(), "REDEEMED", "notif.redeem.ok", hotel.getName(), slot.getName(), before - 1);
        String who = users.findById(c.customerId()).map(com.mealtoken.entity.User::getFullName).orElse("?");
        notifications.notify(hotel.getOwnerId(), "REDEEMED", "notif.owner.meal", who, slot.getName(), before - 1, hotel.getName());
        if (before - 1 == hotel.getLowTokenThreshold() || before - 1 == 0)
            notifications.notify(c.customerId(), "LOW_TOKENS", "notif.tokens.low", before - 1, hotel.getName());

        return new RedemptionResult(a.getId(), a.getReferenceNumber(), hotel.getName(), slot.getName(),
                today, a.getRedeemedAt(), before - 1, false);
    }

    /**
     * Owner-only, audited, idempotent correction of a wrongly recorded meal: restores the token and
     * frees the slot so the meal can legitimately be recorded again.
     */
    @Transactional
    public void reverse(Long hotelId, Long attendanceId, Long actorUserId, String reason) {
        if (reason == null || reason.isBlank()) throw new BusinessException("error.reason.required");
        MealAttendance a = attendance.findByIdAndHotelId(attendanceId, hotelId)
                .orElseThrow(() -> new BusinessException("error.notfound"));
        Subscription sub = subs.lockById(a.getSubscriptionId()).orElseThrow(() -> new BusinessException("error.notfound"));
        em.refresh(a); // another reversal may have committed while we waited for the lock
        if (!"SERVED".equals(a.getStatus())) throw new BusinessException("error.att.alreadyReversed");

        a.setStatus("REVERSED");
        attendance.saveAndFlush(a);

        int before = sub.getRemainingTokens();
        sub.setUsedTokens(sub.getUsedTokens() - 1);
        sub.setRemainingTokens(before + 1);
        if ("EXHAUSTED".equals(sub.getStatus()) && sub.getExpiryDate() != null
                && !subs.existsByCustomerIdAndHotelIdAndStatus(sub.getCustomerId(), sub.getHotelId(), "ACTIVE")) {
            ZoneId zone = ZoneId.of(hotels.findById(hotelId).orElseThrow().getTimezone());
            if (!LocalDate.now(clock.withZone(zone)).isAfter(sub.getExpiryDate())) sub.setStatus("ACTIVE");
        }
        subs.saveAndFlush(sub);

        String trimmed = reason.trim();
        ledger.saveAndFlush(entry(sub.getId(), "REVERSAL", 1, before, before + 1, a.getReferenceNumber(),
                "Reversal: " + (trimmed.length() > 250 ? trimmed.substring(0, 250) : trimmed), actorUserId));
        audit.log(actorUserId, hotelId, "MEAL_REVERSED", "MealAttendance", a.getId(), "ref=" + a.getReferenceNumber());
        notifications.notify(a.getCustomerId(), "REVERSED", "notif.redeem.reversed", a.getReferenceNumber());
    }

    // ------------------------------------------------------------------ helpers

    private RedemptionResult replayOf(String key, Long customerId) {
        if (key == null) return null;
        return attendance.findByIdempotencyKey(key).map(ex -> {
            if (!ex.getCustomerId().equals(customerId)) throw new BusinessException("error.forbidden");
            Hotel h = hotels.findById(ex.getHotelId()).orElseThrow();
            MealSlot s = slots.findById(ex.getMealSlotId()).orElseThrow();
            int remaining = subs.findById(ex.getSubscriptionId()).map(Subscription::getRemainingTokens).orElse(0);
            return new RedemptionResult(ex.getId(), ex.getReferenceNumber(), h.getName(), s.getName(),
                    ex.getAttendanceDate(), ex.getRedeemedAt(), remaining, true);
        }).orElse(null);
    }

    private String diagnose(Long customerId, Long hotelId) {
        return subs.findByCustomerIdAndHotelIdOrderByCreatedAtDesc(customerId, hotelId).stream().findFirst()
                .map(s -> switch (s.getStatus()) {
                    case "EXPIRED" -> "error.sub.expired";
                    case "EXHAUSTED" -> "error.sub.exhausted";
                    case "SUSPENDED" -> "error.sub.suspended";
                    case "PENDING" -> "error.sub.pending";
                    default -> "error.sub.none";
                }).orElse("error.sub.none");
    }

    private static boolean within(MealSlot s, LocalTime t) {
        return !t.isBefore(s.getStartTime()) && !t.isAfter(s.getEndTime());
    }

    static TokenTransaction entry(Long subId, String type, int amount, int before, int after,
                                  String ref, String reason, Long by) {
        TokenTransaction t = new TokenTransaction();
        t.setSubscriptionId(subId);
        t.setTransactionType(type);
        t.setAmount(amount);
        t.setBalanceBefore(before);
        t.setBalanceAfter(after);
        t.setReferenceNumber(ref);
        t.setReason(reason);
        t.setCreatedBy(by);
        return t;
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
