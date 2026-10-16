package com.mealtoken.service;

import com.mealtoken.entity.*;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.*;
import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.Fixtures.World;
import com.mealtoken.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionLifecycleIT extends AbstractIntegrationTest {
    @Autowired Fixtures fx;
    @Autowired SubscriptionService subscriptions;
    @Autowired PaymentService payments;
    @Autowired ExpiryService expiry;
    @Autowired RedemptionService redemption;
    @Autowired SubscriptionRepository subs;
    @Autowired PaymentRepository paymentRepo;
    @Autowired TokenTransactionRepository ledger;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlanService planService;

    @AfterEach void resetClock() { clock.reset(); }

    @Test
    void tokensAreCreditedOnlyAfterPaymentIsConfirmed_planA() {
        World w = fx.newWorld();
        Long customer = fx.newCustomer();

        SubscriptionRequest r = subscriptions.request(customer, w.hotel().getId(), w.planA().getId());
        Subscription s = subscriptions.approve(w.owner(), r.getId());
        assertThat(s.getStatus()).isEqualTo("PENDING");
        assertThat(ledger.findBySubscriptionIdOrderByIdAsc(s.getId())).isEmpty();

        // a PENDING subscription cannot be redeemed
        assertThatThrownBy(() -> redemption.redeem(new RedeemCommand(customer, w.hotel().getId(), w.lunch().getId(), customer, null, "T")))
                .isInstanceOf(BusinessException.class).hasMessage("error.sub.pending");

        payments.confirmManual(w.owner(), payments.findPendingIdFor(s.getId()), "CASH", null);
        Subscription active = subs.findById(s.getId()).orElseThrow();
        assertThat(active.getStatus()).isEqualTo("ACTIVE");
        assertThat(active.getTotalTokens()).isEqualTo(30);
        assertThat(active.getRemainingTokens()).isEqualTo(30);
        assertThat(active.getExpiryDate()).isEqualTo(active.getStartDate().plusDays(29)); // 30 calendar days, inclusive
        assertThat(ledger.findBySubscriptionIdOrderByIdAsc(s.getId())).singleElement()
                .satisfies(t -> { assertThat(t.getTransactionType()).isEqualTo("CREDIT"); assertThat(t.getBalanceAfter()).isEqualTo(30); });
    }

    @Test
    void planBGives60Tokens() {
        World w = fx.newWorld();
        Subscription s = fx.activeSubscription(w, fx.newCustomer(), w.planB());
        assertThat(s.getTotalTokens()).isEqualTo(60);
        assertThat(s.getRemainingTokens()).isEqualTo(60);
    }

    @Test
    void secondActiveSubscriptionAtSameHotelIsRejected() {
        World w = fx.newWorld();
        Long customer = fx.newCustomer();
        fx.activeSubscription(w, customer, w.planA());
        assertThatThrownBy(() -> subscriptions.request(customer, w.hotel().getId(), w.planB().getId()))
                .isInstanceOf(BusinessException.class).hasMessage("error.sub.active.exists");
    }

    @Test
    void duplicatePendingRequestIsRejected() {
        World w = fx.newWorld();
        Long customer = fx.newCustomer();
        subscriptions.request(customer, w.hotel().getId(), w.planA().getId());
        assertThatThrownBy(() -> subscriptions.request(customer, w.hotel().getId(), w.planA().getId()))
                .isInstanceOf(BusinessException.class).hasMessage("error.request.exists");
    }

    @Test
    void inactivePlanCannotBeRequested() {
        World w = fx.newWorld();
        Long customer = fx.newCustomer();
        var p = w.planA();
        planService.save(w.owner(), p.getId(), p.getPlanName(), 1, 30, p.getPrice(), 30, true, false); // deactivate plan A
        assertThatThrownBy(() -> subscriptions.request(customer, w.hotel().getId(), p.getId()))
                .isInstanceOf(BusinessException.class).hasMessage("error.plan.inactive");
    }

    @Test
    void expiredSubscriptionCannotBeRedeemedEvenBeforeTheJobRuns_andJobExpiresIt() {
        World w = fx.newWorld();
        Long customer = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, customer, w.planA());

        clock.advance(Duration.ofDays(31)); // past the 30-day validity, job has NOT run yet
        assertThatThrownBy(() -> redemption.redeem(new RedeemCommand(customer, w.hotel().getId(), w.lunch().getId(), customer, null, "T")))
                .isInstanceOf(BusinessException.class).hasMessage("error.sub.expired");
        assertThat(subs.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");

        assertThat(expiry.process()).isGreaterThanOrEqualTo(1);
        Subscription after = subs.findById(s.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("EXPIRED");
        assertThat(after.getRemainingTokens()).isEqualTo(30); // balance is never silently rewritten
        assertThat(ledger.findBySubscriptionIdOrderByIdAsc(s.getId())).extracting(TokenTransaction::getTransactionType)
                .containsExactly("CREDIT", "EXPIRY");
        // expiring twice is a no-op
        expiry.process();
        assertThat(ledger.findBySubscriptionIdOrderByIdAsc(s.getId())).hasSize(2);
        // expired -> may now request a renewal
        subscriptions.request(customer, w.hotel().getId(), w.planA().getId());
    }

    @Test
    void ownerCanExtendAnExpiredSubscriptionThatStillHasTokens() {
        World w = fx.newWorld();
        Long customer = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, customer, w.planA());
        clock.advance(Duration.ofDays(31));
        expiry.process();
        LocalDate newExpiry = LocalDate.now(clock.withZone(ZoneId.of(w.hotel().getTimezone()))).plusDays(7);
        subscriptions.extend(w.owner(), s.getId(), newExpiry);
        Subscription after = subs.findById(s.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
        assertThat(after.getExpiryDate()).isEqualTo(newExpiry);
    }

    @Test
    void invalidPaymentConfirmationsAreRejected() {
        World w = fx.newWorld();
        World other = fx.newWorld();
        Long customer = fx.newCustomer();
        Subscription s = subscriptions.approve(w.owner(), subscriptions.request(customer, w.hotel().getId(), w.planA().getId()).getId());
        Long paymentId = payments.findPendingIdFor(s.getId());

        assertThatThrownBy(() -> payments.confirmManual(w.owner(), paymentId, "BITCOIN", null))
                .hasMessage("error.payment.method");
        assertThatThrownBy(() -> payments.confirmManual(w.owner(), paymentId, "UPI", "  "))
                .hasMessage("error.payment.reference");
        // another hotel's owner can neither confirm nor reject it, and cannot tell it exists
        assertThatThrownBy(() -> payments.confirmManual(other.owner(), paymentId, "CASH", null)).hasMessage("error.notfound");
        assertThatThrownBy(() -> payments.rejectManual(other.owner(), paymentId)).hasMessage("error.notfound");
        assertThat(subs.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");

        payments.confirmManual(w.owner(), paymentId, "UPI", "UPI-123456");
        assertThat(paymentRepo.findById(paymentId).orElseThrow().getReceiptNumber()).startsWith("RC-");
        // confirming twice must not credit tokens twice
        assertThatThrownBy(() -> payments.confirmManual(w.owner(), paymentId, "CASH", null)).hasMessage("error.payment.state");
        assertThat(ledger.findBySubscriptionIdOrderByIdAsc(s.getId())).hasSize(1);
    }

    @Test
    void rejectedPaymentCancelsThePendingSubscription() {
        World w = fx.newWorld();
        Long customer = fx.newCustomer();
        Subscription s = subscriptions.approve(w.owner(), subscriptions.request(customer, w.hotel().getId(), w.planA().getId()).getId());
        payments.rejectManual(w.owner(), payments.findPendingIdFor(s.getId()));
        assertThat(subs.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(ledger.findBySubscriptionIdOrderByIdAsc(s.getId())).isEmpty();
    }

    @Test
    void staffWithoutPermissionCannotConfirmPayments() {
        World w = fx.newWorld();
        Long customer = fx.newCustomer();
        Subscription s = subscriptions.approve(w.owner(), subscriptions.request(customer, w.hotel().getId(), w.planA().getId()).getId());
        var staff = new com.mealtoken.security.HotelContext(w.hotel(), 999_999L, false, false);
        assertThatThrownBy(() -> payments.confirmManual(staff, payments.findPendingIdFor(s.getId()), "CASH", null))
                .hasMessage("error.forbidden");
    }

    @Test
    void ledgerAndAuditAreAppendOnlyInTheDatabase() {
        World w = fx.newWorld();
        Subscription s = fx.activeSubscription(w, fx.newCustomer(), w.planA());
        assertThatThrownBy(() -> jdbc.update("UPDATE token_transactions SET amount = 999 WHERE subscription_id = ?", s.getId()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM token_transactions WHERE subscription_id = ?", s.getId()))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE hotel_id = ?", w.hotel().getId()))
                .hasMessageContaining("append-only");
    }

    @Test
    void databaseRefusesNegativeBalances() {
        World w = fx.newWorld();
        Subscription s = fx.activeSubscription(w, fx.newCustomer(), w.planA());
        assertThatThrownBy(() -> jdbc.update("UPDATE subscriptions SET remaining_tokens = -1, used_tokens = 31 WHERE id = ?", s.getId()))
                .hasMessageContaining("chk_tokens");
    }
}
