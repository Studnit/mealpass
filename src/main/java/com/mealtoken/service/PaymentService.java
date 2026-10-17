package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.Hotel;
import com.mealtoken.entity.Payment;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.notification.NotificationService;
import com.mealtoken.repository.HotelRepository;
import com.mealtoken.repository.PaymentRepository;
import com.mealtoken.security.HotelContext;
import com.mealtoken.util.Refs;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Set;

@Service
public class PaymentService {
    private static final Set<String> MANUAL_METHODS = Set.of("CASH", "UPI");

    private final PaymentRepository payments;
    private final HotelRepository hotels;
    private final SubscriptionService subscriptions;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;

    public PaymentService(PaymentRepository payments, HotelRepository hotels, SubscriptionService subscriptions,
                          AuditService audit, NotificationService notifications, Clock clock) {
        this.payments = payments; this.hotels = hotels; this.subscriptions = subscriptions;
        this.audit = audit; this.notifications = notifications; this.clock = clock;
    }

    /** Customer attaches the UPI transaction reference of a payment they made; still needs staff verification. */
    @Transactional
    public void submitUpiReference(Long customerId, Long paymentId, String reference) {
        Payment p = payments.findByIdAndCustomerId(paymentId, customerId).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!"PENDING".equals(p.getStatus())) throw new BusinessException("error.payment.state");
        String ref = clean(reference);
        if (ref == null) throw new BusinessException("error.payment.reference");
        p.setPaymentMethod("UPI");
        p.setPaymentReference(ref);
        payments.save(p);
        Hotel h = hotels.findById(p.getHotelId()).orElseThrow();
        audit.log(customerId, p.getHotelId(), "PAYMENT_REF_SUBMITTED", "Payment", p.getId(), null);
        notifications.notify(h.getOwnerId(), "PAYMENT", "notif.pay.submitted", p.getAmount());
    }

    /** Cash / UPI confirmed for the full amount by the owner or an authorised staff member. */
    @Transactional
    public void confirmManual(HotelContext ctx, Long paymentId, String method, String reference) {
        confirmManual(ctx, paymentId, method, reference, null, true);
    }

    /**
     * Records money received against a PENDING payment (which always equals the amount still due).
     * {@code received == null} or equal to the due amount settles it. A smaller amount is a part payment: this record is
     * settled for what was received and a new PENDING record carries the balance. A part payment starts the subscription
     * only when {@code activateNow} is true; a full payment always does.
     */
    @Transactional
    public void confirmManual(HotelContext ctx, Long paymentId, String method, String reference, BigDecimal received, boolean activateNow) {
        if (!ctx.owner() && !ctx.canConfirmPayments()) throw new BusinessException("error.forbidden");
        String m = method == null ? "" : method.trim().toUpperCase();
        if (!MANUAL_METHODS.contains(m)) throw new BusinessException("error.payment.method");
        String ref = clean(reference);
        if ("UPI".equals(m) && ref == null) throw new BusinessException("error.payment.reference");

        Payment p = payments.lockById(paymentId).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!p.getHotelId().equals(ctx.hotelId())) throw new BusinessException("error.notfound");
        if (!"PENDING".equals(p.getStatus())) throw new BusinessException("error.payment.state");
        BigDecimal due = p.getAmount();
        BigDecimal got = received == null ? due : received.setScale(2, java.math.RoundingMode.HALF_UP);
        if (got.signum() <= 0 || got.compareTo(due) > 0) throw new BusinessException("error.payment.amount");
        boolean full = got.compareTo(due) == 0;
        String originalMethod = p.getPaymentMethod();
        if (!full) p.setAmount(got);
        markPaid(p, m, ref, ctx.userId(), null);
        if (!full) {                                         // the balance stays payable as a new PENDING record
            Payment rest = new Payment();
            rest.setCustomerId(p.getCustomerId()); rest.setHotelId(p.getHotelId()); rest.setSubscriptionId(p.getSubscriptionId());
            rest.setAmount(due.subtract(got)); rest.setPaymentMethod(originalMethod);
            payments.saveAndFlush(rest);
        }
        audit.log(ctx.userId(), ctx.hotelId(), full ? "PAYMENT_CONFIRMED" : "PAYMENT_PART_RECEIVED", "Payment", p.getId(),
                "method=" + m + " amount=" + got + (full ? "" : " balance=" + due.subtract(got)));
        if (full) notifications.notify(p.getCustomerId(), "PAYMENT", "notif.pay.confirmed", got, ctx.hotel().getName());
        else notifications.notify(p.getCustomerId(), "PAYMENT", "notif.pay.partial", got, ctx.hotel().getName(), due.subtract(got));
        if (p.getSubscriptionId() != null) {
            if ((full || activateNow) && subscriptions.isPending(p.getSubscriptionId()))
                subscriptions.activate(p.getSubscriptionId(), ctx.userId());
            subscriptions.syncPaymentStatus(p.getSubscriptionId());
        }
    }

    /**
     * Owner/staff records a payment against a subscription's outstanding balance (creating the PENDING record if the
     * previous one was rejected). Used by the Billing screen. Only the hotel's own subscriptions can be addressed.
     */
    @Transactional
    public void receive(HotelContext ctx, Long subscriptionId, BigDecimal amount, String method, String reference, boolean activateNow) {
        if (!ctx.owner() && !ctx.canConfirmPayments()) throw new BusinessException("error.forbidden");
        var s = subscriptions.findForHotel(ctx.hotelId(), subscriptionId);
        if ("CANCELLED".equals(s.getStatus())) throw new BusinessException("error.payment.state");
        BigDecimal due = s.getTotalAmount().subtract(payments.sumPaid(subscriptionId));
        if (due.signum() <= 0) throw new BusinessException("error.payment.state");
        Payment pending = payments.findFirstBySubscriptionIdAndStatusOrderByIdDesc(subscriptionId, "PENDING").orElseGet(() -> {
            Payment n = new Payment();
            n.setCustomerId(s.getCustomerId()); n.setHotelId(s.getHotelId()); n.setSubscriptionId(subscriptionId);
            n.setPaymentMethod("UNSET");
            return n;
        });
        pending.setAmount(due);                             // the open record always equals what is still owed
        pending = payments.saveAndFlush(pending);
        confirmManual(ctx, pending.getId(), method, reference, amount, activateNow);
    }

    @Transactional
    public void rejectManual(HotelContext ctx, Long paymentId) {
        if (!ctx.owner() && !ctx.canConfirmPayments()) throw new BusinessException("error.forbidden");
        Payment p = payments.lockById(paymentId).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!p.getHotelId().equals(ctx.hotelId())) throw new BusinessException("error.notfound");
        if (!"PENDING".equals(p.getStatus())) throw new BusinessException("error.payment.state");
        fail(p, ctx.userId());
        audit.log(ctx.userId(), ctx.hotelId(), "PAYMENT_REJECTED", "Payment", p.getId(), null);
        notifications.notify(p.getCustomerId(), "PAYMENT", "notif.pay.rejected", ctx.hotel().getName());
    }

    /**
     * Result of a server-to-server provider callback whose signature has already been verified.
     * Idempotent: a repeated SUCCESS for an already-paid payment is a no-op.
     */
    @Transactional
    public void applyGatewayResult(Long paymentId, String provider, String reference, boolean success, BigDecimal amount) {
        Payment p = payments.lockById(paymentId).orElseThrow(() -> new BusinessException("error.notfound"));
        if ("SUCCESS".equals(p.getStatus())) return;
        if (!"PENDING".equals(p.getStatus())) throw new BusinessException("error.payment.state");
        if (success) {
            if (amount == null || p.getAmount().compareTo(amount) != 0) throw new BusinessException("error.payment.amount");
            markPaid(p, "ONLINE", clean(reference), null, provider);
            audit.log(null, p.getHotelId(), "PAYMENT_CONFIRMED", "Payment", p.getId(), "gateway=" + provider);
            Hotel h = hotels.findById(p.getHotelId()).orElseThrow();
            notifications.notify(p.getCustomerId(), "PAYMENT", "notif.pay.confirmed", p.getAmount(), h.getName());
            if (p.getSubscriptionId() != null) {
                if (subscriptions.isPending(p.getSubscriptionId())) subscriptions.activate(p.getSubscriptionId(), null);
                subscriptions.syncPaymentStatus(p.getSubscriptionId());
            }
        } else {
            fail(p, null);
            audit.log(null, p.getHotelId(), "PAYMENT_FAILED", "Payment", p.getId(), "gateway=" + provider);
        }
    }

    /** Id of the PENDING payment attached to a subscription (used by data seeding and tests). */
    @Transactional(readOnly = true)
    public Long findPendingIdFor(Long subscriptionId) {
        return payments.findFirstBySubscriptionIdAndStatusOrderByIdDesc(subscriptionId, "PENDING")
                .map(Payment::getId).orElseThrow(() -> new BusinessException("error.notfound"));
    }

    private void markPaid(Payment p, String method, String ref, Long verifier, String provider) {
        p.setPaymentMethod(method);
        p.setPaymentReference(ref != null ? ref : p.getPaymentReference());
        p.setStatus("SUCCESS");
        p.setVerifiedBy(verifier);
        p.setPaidAt(clock.instant());
        p.setReceiptNumber(Refs.next("RC"));
        p.setGatewayProvider(provider);
        payments.saveAndFlush(p);
    }

    private void fail(Payment p, Long actor) {
        p.setStatus("FAILED");
        p.setVerifiedBy(actor);
        payments.saveAndFlush(p);
        if (p.getSubscriptionId() != null) subscriptions.cancelPending(p.getSubscriptionId(), actor);
    }

    private static String clean(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > 100 ? t.substring(0, 100) : t;
    }
}
