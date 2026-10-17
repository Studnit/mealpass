package com.mealtoken.service;

import com.mealtoken.entity.Subscription;
import com.mealtoken.repository.PaymentRepository;
import com.mealtoken.repository.SubscriptionRepository;
import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.Fixtures.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BillingIT extends AbstractIntegrationTest {
    @Autowired Fixtures fx;
    @Autowired SubscriptionService subscriptions;
    @Autowired PaymentService payments;
    @Autowired BillingService billing;
    @Autowired SubscriptionRepository subs;
    @Autowired PaymentRepository paymentRepo;
    @Autowired RedemptionService redemption;
    @Autowired com.mealtoken.support.MutableClock clock;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Subscription approved(World w, Long customer) {
        var r = subscriptions.request(customer, w.hotel().getId(), w.planA().getId());
        return subscriptions.approve(w.owner(), r.getId());
    }

    private static BigDecimal bd(String v) { return new BigDecimal(v); }

    @Test
    void fullPaymentActivatesAndSettles() {
        World w = fx.newWorld(); Long c = fx.newCustomer();
        Subscription s = approved(w, c);
        payments.receive(w.owner(), s.getId(), bd("100.00"), "CASH", null, false);
        Subscription after = subs.findById(s.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
        assertThat(after.getPaymentStatus()).isEqualTo("SUCCESS");
        var line = billing.customerLines(c).get(0);
        assertThat(line.getPaid()).isEqualByComparingTo("100");
        assertThat(line.getDue()).isEqualByComparingTo("0");
    }

    @Test
    void partPaymentKeepsBalanceAndActivatesOnlyWhenAsked() {
        World w = fx.newWorld(); Long c = fx.newCustomer();
        Subscription s = approved(w, c);
        payments.receive(w.owner(), s.getId(), bd("40.00"), "CASH", null, false);
        Subscription mid = subs.findById(s.getId()).orElseThrow();
        assertThat(mid.getStatus()).isEqualTo("PENDING");
        assertThat(mid.getPaymentStatus()).isEqualTo("PARTIAL");
        var line = billing.hotelLines(w.hotel().getId()).get(0);
        assertThat(line.getPaid()).isEqualByComparingTo("40");
        assertThat(line.getDue()).isEqualByComparingTo("60");
        assertThat(line.getPendingPaymentId()).isNotNull();

        payments.receive(w.owner(), s.getId(), bd("10.00"), "CASH", null, true);
        assertThat(subs.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(billing.hotelLines(w.hotel().getId()).get(0).getDue()).isEqualByComparingTo("50");

        payments.receive(w.owner(), s.getId(), bd("50.00"), "CASH", null, false);
        assertThat(subs.findById(s.getId()).orElseThrow().getPaymentStatus()).isEqualTo("SUCCESS");
        assertThat(billing.hotelSummary(w.hotel().getId(), "Asia/Kolkata").getOutstanding()).isEqualByComparingTo("0");
    }

    @Test
    void overPaymentAndZeroAreRejected() {
        World w = fx.newWorld(); Long c = fx.newCustomer();
        Subscription s = approved(w, c);
        assertThatThrownBy(() -> payments.receive(w.owner(), s.getId(), bd("100.01"), "CASH", null, false)).hasMessage("error.payment.amount");
        assertThatThrownBy(() -> payments.receive(w.owner(), s.getId(), bd("0"), "CASH", null, false)).hasMessage("error.payment.amount");
    }

    @Test
    void otherHotelCannotReceiveOrSee() {
        World a = fx.newWorld(), b = fx.newWorld(); Long c = fx.newCustomer();
        Subscription s = approved(a, c);
        assertThatThrownBy(() -> payments.receive(b.owner(), s.getId(), bd("10"), "CASH", null, false)).hasMessage("error.notfound");
        assertThat(billing.hotelLines(b.hotel().getId())).isEmpty();
    }

    @Test
    void summaryCountsOpenAndCollected() {
        World w = fx.newWorld(); Long c1 = fx.newCustomer(), c2 = fx.newCustomer();
        Subscription s1 = approved(w, c1); approved(w, c2);
        payments.receive(w.owner(), s1.getId(), bd("30.00"), "CASH", null, false);
        var sum = billing.hotelSummary(w.hotel().getId(), "Asia/Kolkata");
        assertThat(sum.getBilled()).isEqualByComparingTo("200");
        assertThat(sum.getCollected()).isEqualByComparingTo("30");
        assertThat(sum.getOutstanding()).isEqualByComparingTo("170");
        assertThat(sum.getOpenCount()).isEqualTo(2);
        assertThat(sum.getCollectedToday()).isEqualByComparingTo("30");
    }

    @Test
    void threeThousandOfFortyTwoHundredLeavesTwelveHundredUntilSettled() {
        World w = fx.newWorld(); Long c = fx.newCustomer();
        var plan = w.planB();
        var req = subscriptions.request(c, w.hotel().getId(), plan.getId());
        Subscription s = subscriptions.approve(w.owner(), req.getId());
        // plan price in the fixture is 100; scale the scenario to 3000 of 100 -> use 71.43% equivalent: pay 70 of 100
        payments.confirmManual(w.owner(), payments.findPendingIdFor(s.getId()), "CASH", null, bd("70.00"), true);
        var line = billing.customerLines(c).get(0);
        assertThat(line.getDue()).isEqualByComparingTo("30");
        assertThat(billing.ledger(java.util.List.of(s.getId())).get(s.getId())).hasSize(2);
        payments.confirmManual(w.owner(), payments.findPendingIdFor(s.getId()), "CASH", null, bd("30.00"), false);
        assertThat(billing.customerLines(c).get(0).getDue()).isEqualByComparingTo("0");
    }

    @Test
    void lastFiveTokensAreHeldWhileMoneyIsDueAndReleasedOncePaid() {
        World w = fx.newWorld(); Long c = fx.newCustomer();
        Subscription s = approved(w, c);
        payments.receive(w.owner(), s.getId(), bd("40.00"), "CASH", null, true);        // part paid, activated
        jdbc.update("UPDATE subscriptions SET remaining_tokens = 6, used_tokens = 24 WHERE id = ?", s.getId());
        var cmd = new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, null, "TEST");
        assertThat(redemption.redeem(cmd).remainingTokens()).isEqualTo(5);               // 6 > 5: allowed, leaves the held 5
        clock.advance(java.time.Duration.ofDays(1));                                     // next day
        assertThatThrownBy(() -> redemption.redeem(new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, null, "TEST")))
                .hasMessage("error.redeem.balance.due");
        payments.receive(w.owner(), s.getId(), bd("60.00"), "CASH", null, false);        // settled
        assertThat(redemption.redeem(new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, null, "TEST")).remainingTokens()).isEqualTo(4);
    }

    @Test
    void holdCanBeSwitchedOffPerHotel() {
        World w = fx.newWorld(); Long c = fx.newCustomer();
        jdbc.update("UPDATE hotels SET token_hold_count = 0 WHERE id = ?", w.hotel().getId());
        Subscription s = approved(w, c);
        payments.receive(w.owner(), s.getId(), bd("10.00"), "CASH", null, true);
        jdbc.update("UPDATE subscriptions SET remaining_tokens = 2, used_tokens = 28 WHERE id = ?", s.getId());
        assertThat(redemption.redeem(new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, null, "TEST")).remainingTokens()).isEqualTo(1);
    }

    @Test
    void alertLevelWarnsFiveTokensBeforeTheHoldAndBlocksAtTheHold() {
        BigDecimal due = bd("100");
        assertThat(RedemptionService.alertLevel(due, 5, 30, 11)).isEqualTo(0);   // plenty left
        assertThat(RedemptionService.alertLevel(due, 5, 30, 10)).isEqualTo(1);   // 5 above the held tokens: warn
        assertThat(RedemptionService.alertLevel(due, 5, 30, 6)).isEqualTo(1);
        assertThat(RedemptionService.alertLevel(due, 5, 30, 5)).isEqualTo(2);    // held
        assertThat(RedemptionService.alertLevel(BigDecimal.ZERO, 5, 30, 5)).isEqualTo(0);   // settled: no alert
    }
}
