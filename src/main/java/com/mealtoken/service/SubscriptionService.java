package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.*;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.notification.NotificationService;
import com.mealtoken.repository.*;
import com.mealtoken.security.HotelContext;
import com.mealtoken.util.Refs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Service
public class SubscriptionService {
    private final SubscriptionRepository subs;
    private final SubscriptionRequestRepository requests;
    private final SubscriptionPlanRepository plans;
    private final HotelRepository hotels;
    private final PaymentRepository payments;
    private final TokenTransactionRepository ledger;
    private final UserRepository users;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public SubscriptionService(SubscriptionRepository subs, SubscriptionRequestRepository requests,
                               SubscriptionPlanRepository plans, HotelRepository hotels, PaymentRepository payments,
                               TokenTransactionRepository ledger, UserRepository users, AuditService audit,
                               NotificationService notifications, Clock clock) {
        this.subs = subs; this.requests = requests; this.plans = plans; this.hotels = hotels;
        this.payments = payments; this.ledger = ledger; this.users = users; this.audit = audit;
        this.notifications = notifications; this.clock = clock;
    }

    // ---------------------------------------------------------------- customer side

    @Transactional
    public SubscriptionRequest request(Long customerId, Long hotelId, Long planId) {
        Hotel hotel = hotels.findById(hotelId).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!"ACTIVE".equals(hotel.getStatus())) throw new BusinessException("error.hotel.inactive");
        SubscriptionPlan plan = plans.findByIdAndHotelId(planId, hotelId).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!plan.isActive()) throw new BusinessException("error.plan.inactive");
        if (subs.existsByCustomerIdAndHotelIdAndStatus(customerId, hotelId, "ACTIVE"))
            throw new BusinessException("error.sub.active.exists");
        if (subs.existsByCustomerIdAndHotelIdAndStatus(customerId, hotelId, "PENDING")
                || requests.existsByCustomerIdAndHotelIdAndStatus(customerId, hotelId, "PENDING"))
            throw new BusinessException("error.request.exists");

        SubscriptionRequest r = new SubscriptionRequest();
        r.setCustomerId(customerId);
        r.setHotelId(hotelId);
        r.setPlanId(planId);
        r = requests.save(r);
        String name = users.findById(customerId).map(User::getFullName).orElse("?");
        notifications.notify(hotel.getOwnerId(), "REQUEST", "notif.req.new", name, plan.getPlanName(), hotel.getName());
        notifications.notify(customerId, "REQUEST", "notif.req.submitted", hotel.getName(), plan.getPlanName());
        audit.log(customerId, hotelId, "SUBSCRIPTION_REQUESTED", "SubscriptionRequest", r.getId(), "plan=" + plan.getPlanName());
        return r;
    }

    // ---------------------------------------------------------------- owner side

    /** Approving creates a PENDING subscription plus a PENDING payment; tokens are credited only on payment. */
    @Transactional
    public Subscription approve(HotelContext ctx, Long requestId) {
        requireOwner(ctx);
        SubscriptionRequest r = requests.findByIdAndHotelId(requestId, ctx.hotelId())
                .orElseThrow(() -> new BusinessException("error.notfound"));
        if (!"PENDING".equals(r.getStatus())) throw new BusinessException("error.sub.state");
        SubscriptionPlan plan = plans.findById(r.getPlanId()).orElseThrow(() -> new BusinessException("error.notfound"));

        Subscription s = new Subscription();
        s.setCustomerId(r.getCustomerId());
        s.setHotelId(ctx.hotelId());
        s.setPlanId(plan.getId());
        s.setTotalTokens(plan.getTotalTokens());
        s.setUsedTokens(0);
        s.setRemainingTokens(plan.getTotalTokens());
        s.setStatus("PENDING");
        s.setPaymentStatus("PENDING");
        s.setTotalAmount(plan.getPrice());          // fixed now: later plan price edits never change what is owed
        s = subs.saveAndFlush(s);

        Payment p = new Payment();
        p.setCustomerId(r.getCustomerId());
        p.setHotelId(ctx.hotelId());
        p.setSubscriptionId(s.getId());
        p.setAmount(plan.getPrice());
        p.setPaymentMethod("UNSET");
        p = payments.saveAndFlush(p);

        r.setStatus("APPROVED");
        r.setDecidedBy(ctx.userId());
        r.setDecidedAt(Instant.now());
        requests.save(r);

        audit.log(ctx.userId(), ctx.hotelId(), "REQUEST_APPROVED", "SubscriptionRequest", r.getId(), "subscription=" + s.getId());
        notifications.notify(r.getCustomerId(), "REQUEST", "notif.req.approved", ctx.hotel().getName());

        if (plan.getPrice().compareTo(BigDecimal.ZERO) == 0) { // free plan: nothing to collect
            p.setPaymentMethod("FREE");
            p.setStatus("SUCCESS");
            p.setPaidAt(Instant.now());
            p.setVerifiedBy(ctx.userId());
            p.setReceiptNumber(Refs.next("RC"));
            payments.save(p);
            activate(s.getId(), ctx.userId());
        }
        return s;
    }

    @Transactional
    public void reject(HotelContext ctx, Long requestId, String note) {
        requireOwner(ctx);
        SubscriptionRequest r = requests.findByIdAndHotelId(requestId, ctx.hotelId())
                .orElseThrow(() -> new BusinessException("error.notfound"));
        if (!"PENDING".equals(r.getStatus())) throw new BusinessException("error.sub.state");
        r.setStatus("REJECTED");
        r.setNote(note == null ? null : (note.length() > 300 ? note.substring(0, 300) : note));
        r.setDecidedBy(ctx.userId());
        r.setDecidedAt(Instant.now());
        requests.save(r);
        audit.log(ctx.userId(), ctx.hotelId(), "REQUEST_REJECTED", "SubscriptionRequest", r.getId(), null);
        notifications.notify(r.getCustomerId(), "REQUEST", "notif.req.rejected", ctx.hotel().getName());
    }

    /** PENDING -> ACTIVE. Called only after payment is confirmed. Credits the tokens in the ledger. */
    @Transactional
    public Subscription activate(Long subscriptionId, Long actorUserId) {
        Subscription s = subs.lockById(subscriptionId).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!"PENDING".equals(s.getStatus())) throw new BusinessException("error.sub.state");
        if (subs.existsByCustomerIdAndHotelIdAndStatus(s.getCustomerId(), s.getHotelId(), "ACTIVE"))
            throw new BusinessException("error.sub.active.exists");
        SubscriptionPlan plan = plans.findById(s.getPlanId()).orElseThrow();
        Hotel hotel = hotels.findById(s.getHotelId()).orElseThrow();

        LocalDate start = LocalDate.now(clock.withZone(ZoneId.of(hotel.getTimezone())));
        s.setStartDate(start);
        s.setExpiryDate(start.plusDays(plan.getValidityDays() - 1L)); // validity counted inclusively
        s.setStatus("ACTIVE");
        s.setPaymentStatus("SUCCESS");
        subs.saveAndFlush(s);

        ledger.save(RedemptionService.entry(s.getId(), "CREDIT", s.getTotalTokens(), 0, s.getTotalTokens(),
                Refs.next("CR"), "Subscription activated: " + plan.getPlanName(), actorUserId));
        audit.log(actorUserId, s.getHotelId(), "SUBSCRIPTION_ACTIVATED", "Subscription", s.getId(),
                "expiry=" + s.getExpiryDate());
        notifications.notify(s.getCustomerId(), "SUBSCRIPTION", "notif.sub.activated", hotel.getName(), s.getExpiryDate());
        return s;
    }

    /** Keeps subscriptions.payment_status in step with the money received: PENDING, PARTIAL or SUCCESS (fully paid). */
    @Transactional
    public void syncPaymentStatus(Long subscriptionId) {
        Subscription s = subs.lockById(subscriptionId).orElseThrow(() -> new BusinessException("error.notfound"));
        BigDecimal paid = payments.sumPaid(subscriptionId);
        s.setPaymentStatus(paid.compareTo(s.getTotalAmount()) >= 0 ? "SUCCESS" : paid.signum() > 0 ? "PARTIAL" : "PENDING");
        subs.save(s);
    }

    /** A subscription of this hotel, or "not found" (never reveals other hotels' rows). */
    @Transactional(readOnly = true)
    public Subscription findForHotel(Long hotelId, Long subscriptionId) {
        return subs.findById(subscriptionId).filter(x -> x.getHotelId().equals(hotelId))
                .orElseThrow(() -> new BusinessException("error.notfound"));
    }

    @Transactional(readOnly = true)
    public boolean isPending(Long subscriptionId) {
        return subs.findById(subscriptionId).map(x -> "PENDING".equals(x.getStatus())).orElse(false);
    }

    @Transactional
    public void suspend(HotelContext ctx, Long subscriptionId) {
        Subscription s = ownedLocked(ctx, subscriptionId);
        if (!"ACTIVE".equals(s.getStatus())) throw new BusinessException("error.sub.state");
        s.setStatus("SUSPENDED");
        audit.log(ctx.userId(), ctx.hotelId(), "SUBSCRIPTION_SUSPENDED", "Subscription", s.getId(), null);
        notifications.notify(s.getCustomerId(), "SUBSCRIPTION", "notif.sub.suspended", ctx.hotel().getName());
    }

    @Transactional
    public void resume(HotelContext ctx, Long subscriptionId) {
        Subscription s = ownedLocked(ctx, subscriptionId);
        if (!"SUSPENDED".equals(s.getStatus())) throw new BusinessException("error.sub.state");
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(ctx.hotel().getTimezone())));
        if (s.getExpiryDate() == null || today.isAfter(s.getExpiryDate())) throw new BusinessException("error.sub.expired");
        if (subs.existsByCustomerIdAndHotelIdAndStatus(s.getCustomerId(), s.getHotelId(), "ACTIVE"))
            throw new BusinessException("error.sub.active.exists");
        s.setStatus(s.getRemainingTokens() > 0 ? "ACTIVE" : "EXHAUSTED");
        audit.log(ctx.userId(), ctx.hotelId(), "SUBSCRIPTION_RESUMED", "Subscription", s.getId(), null);
    }

    @Transactional
    public void cancel(HotelContext ctx, Long subscriptionId) {
        Subscription s = ownedLocked(ctx, subscriptionId);
        if (List.of("CANCELLED", "EXPIRED").contains(s.getStatus())) throw new BusinessException("error.sub.state");
        s.setStatus("CANCELLED");
        audit.log(ctx.userId(), ctx.hotelId(), "SUBSCRIPTION_CANCELLED", "Subscription", s.getId(),
                "remaining=" + s.getRemainingTokens());
    }

    /** Owner-authorised extension: also reactivates an EXPIRED subscription that still has tokens. */
    @Transactional
    public void extend(HotelContext ctx, Long subscriptionId, LocalDate newExpiry) {
        Subscription s = ownedLocked(ctx, subscriptionId);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(ctx.hotel().getTimezone())));
        if (newExpiry == null || newExpiry.isBefore(today) || !List.of("ACTIVE", "EXPIRED").contains(s.getStatus()))
            throw new BusinessException("error.date.invalid");
        if ("EXPIRED".equals(s.getStatus())) {
            if (s.getRemainingTokens() <= 0) throw new BusinessException("error.sub.exhausted");
            if (subs.existsByCustomerIdAndHotelIdAndStatus(s.getCustomerId(), s.getHotelId(), "ACTIVE"))
                throw new BusinessException("error.sub.active.exists");
            s.setStatus("ACTIVE");
        }
        s.setExpiryDate(newExpiry);
        audit.log(ctx.userId(), ctx.hotelId(), "SUBSCRIPTION_EXTENDED", "Subscription", s.getId(), "expiry=" + newExpiry);
    }

    /** Called when a payment is rejected: the never-activated subscription is cancelled. */
    @Transactional
    public void cancelPending(Long subscriptionId, Long actorUserId) {
        Subscription s = subs.lockById(subscriptionId).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!"PENDING".equals(s.getStatus())) return;
        s.setStatus("CANCELLED");
        s.setPaymentStatus("FAILED");
        audit.log(actorUserId, s.getHotelId(), "SUBSCRIPTION_CANCELLED", "Subscription", s.getId(), "payment failed");
    }

    private Subscription ownedLocked(HotelContext ctx, Long id) {
        requireOwner(ctx);
        Subscription s = subs.lockById(id).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!s.getHotelId().equals(ctx.hotelId())) throw new BusinessException("error.notfound"); // never reveal other hotels' rows
        return s;
    }

    private static void requireOwner(HotelContext ctx) {
        if (!ctx.owner()) throw new BusinessException("error.forbidden");
    }
}
