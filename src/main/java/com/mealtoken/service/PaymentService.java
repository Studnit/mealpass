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

    /** Cash / UPI confirmed by the owner or an authorised staff member. */
    @Transactional
    public void confirmManual(HotelContext ctx, Long paymentId, String method, String reference) {
        if (!ctx.owner() && !ctx.canConfirmPayments()) throw new BusinessException("error.forbidden");
        String m = method == null ? "" : method.trim().toUpperCase();
        if (!MANUAL_METHODS.contains(m)) throw new BusinessException("error.payment.method");
        String ref = clean(reference);
        if ("UPI".equals(m) && ref == null) throw new BusinessException("error.payment.reference");

        Payment p = payments.lockById(paymentId).orElseThrow(() -> new BusinessException("error.notfound"));
        if (!p.getHotelId().equals(ctx.hotelId())) throw new BusinessException("error.notfound");
        if (!"PENDING".equals(p.getStatus())) throw new BusinessException("error.payment.state");
        markPaid(p, m, ref, ctx.userId(), null);
        audit.log(ctx.userId(), ctx.hotelId(), "PAYMENT_CONFIRMED", "Payment", p.getId(), "method=" + m + " amount=" + p.getAmount());
        notifications.notify(p.getCustomerId(), "PAYMENT", "notif.pay.confirmed", p.getAmount(), ctx.hotel().getName());
        if (p.getSubscriptionId() != null) subscriptions.activate(p.getSubscriptionId(), ctx.userId());
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
            if (p.getSubscriptionId() != null) subscriptions.activate(p.getSubscriptionId(), null);
        } else {
            fail(p, null);
            audit.log(null, p.getHotelId(), "PAYMENT_FAILED", "Payment", p.getId(), "gateway=" + provider);
        }
    }

    /** Id of the PENDING payment attached to a subscription (used by data seeding and tests). */
    @Transactional(readOnly = true)
    public Long findPendingIdFor(Long subscriptionId) {
        return payments.findBySubscriptionId(subscriptionId).filter(p -> "PENDING".equals(p.getStatus()))
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
