package com.mealtoken.service;

import com.mealtoken.entity.*;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.*;
import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.Fixtures.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RedemptionServiceIT extends AbstractIntegrationTest {
    @Autowired Fixtures fx;
    @Autowired RedemptionService redemption;
    @Autowired SubscriptionRepository subs;
    @Autowired MealAttendanceRepository attendance;
    @Autowired TokenTransactionRepository ledger;
    @Autowired AuditLogRepository audits;
    @Autowired NotificationRepository notifications;
    @Autowired QrService qr;
    @Autowired JdbcTemplate jdbc;

    private RedeemCommand cmd(World w, Long customer, MealSlot slot, String key) {
        return new RedeemCommand(customer, w.hotel().getId(), slot.getId(), customer, key, "TEST");
    }

    @Test
    void successfulRedemptionDeductsExactlyOneToken_andWritesAttendanceLedgerAuditAndNotification() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planA());

        RedemptionResult r = redemption.redeem(cmd(w, c, w.lunch(), "k1"));

        assertThat(r.remainingTokens()).isEqualTo(29);
        assertThat(r.referenceNumber()).startsWith("MA-");
        Subscription after = subs.findById(s.getId()).orElseThrow();
        assertThat(after.getUsedTokens()).isEqualTo(1);
        assertThat(after.getRemainingTokens()).isEqualTo(29);
        assertThat(ledger.findBySubscriptionIdOrderByIdAsc(s.getId())).extracting(TokenTransaction::getTransactionType)
                .containsExactly("CREDIT", "DEBIT");
        TokenTransaction debit = ledger.findBySubscriptionIdOrderByIdAsc(s.getId()).get(1);
        assertThat(debit.getBalanceBefore()).isEqualTo(30);
        assertThat(debit.getBalanceAfter()).isEqualTo(29);
        assertThat(debit.getReferenceNumber()).isEqualTo(r.referenceNumber());
        assertThat(attendance.findById(r.attendanceId()).orElseThrow().getStatus()).isEqualTo("SERVED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE hotel_id = ? AND action = 'MEAL_REDEEMED'", Long.class, w.hotel().getId()))
                .isEqualTo(1L);
        assertThat(notifications.countByUserIdAndReadFalse(c)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void sameIdempotencyKeyReturnsTheOriginalReceiptAndDoesNotDeductTwice() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planA());
        RedemptionResult first = redemption.redeem(cmd(w, c, w.lunch(), "same-key"));
        RedemptionResult again = redemption.redeem(cmd(w, c, w.lunch(), "same-key"));
        assertThat(again.replay()).isTrue();
        assertThat(again.referenceNumber()).isEqualTo(first.referenceNumber());
        assertThat(subs.findById(s.getId()).orElseThrow().getRemainingTokens()).isEqualTo(29);
    }

    @Test
    void duplicateSlotOnTheSameDayIsRejectedWithoutDeduction() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planB());
        redemption.redeem(cmd(w, c, w.lunch(), "a"));
        assertThatThrownBy(() -> redemption.redeem(cmd(w, c, w.lunch(), "b")))
                .isInstanceOf(BusinessException.class).hasMessage("error.slot.duplicate");
        assertThat(subs.findById(s.getId()).orElseThrow().getRemainingTokens()).isEqualTo(59);
    }

    @Test
    void planAAllowsOnlyOneMealPerDay_planBAllowsTwo() {
        World w = fx.newWorld();
        Long a = fx.newCustomer(), b = fx.newCustomer();
        Subscription sa = fx.activeSubscription(w, a, w.planA());
        Subscription sb = fx.activeSubscription(w, b, w.planB());

        redemption.redeem(cmd(w, a, w.lunch(), "a1"));
        assertThatThrownBy(() -> redemption.redeem(cmd(w, a, w.dinner(), "a2"))).hasMessage("error.daily.limit");
        assertThat(subs.findById(sa.getId()).orElseThrow().getRemainingTokens()).isEqualTo(29);

        redemption.redeem(cmd(w, b, w.lunch(), "b1"));
        redemption.redeem(cmd(w, b, w.dinner(), "b2"));
        assertThat(subs.findById(sb.getId()).orElseThrow().getRemainingTokens()).isEqualTo(58);
    }

    @Test
    void subscriptionFromHotelAIsNotRedeemableAtHotelB() {
        World a = fx.newWorld();
        World b = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(a, c, a.planA());
        assertThatThrownBy(() -> redemption.redeem(cmd(b, c, b.lunch(), "x")))
                .isInstanceOf(BusinessException.class).hasMessage("error.sub.none");
        assertThat(subs.findById(s.getId()).orElseThrow().getRemainingTokens()).isEqualTo(30);
        // and a slot id belonging to hotel B can never be used against hotel A
        assertThatThrownBy(() -> redemption.redeem(new RedeemCommand(c, a.hotel().getId(), b.lunch().getId(), c, "y", "TEST")))
                .hasMessage("error.slot.invalid");
    }

    @Test
    void customerWithoutSubscriptionIsRejected() {
        World w = fx.newWorld();
        assertThatThrownBy(() -> redemption.redeem(cmd(w, fx.newCustomer(), w.lunch(), "z"))).hasMessage("error.sub.none");
    }

    @Test
    void lastTokenExhaustsSubscription_thenFurtherRedemptionsFailAndBalanceNeverGoesNegative() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planB());
        // leave exactly one token (operator-style direct setup keeps the CHECK invariant used+remaining=total)
        jdbc.update("UPDATE subscriptions SET used_tokens = 59, remaining_tokens = 1 WHERE id = ?", s.getId());

        redemption.redeem(cmd(w, c, w.lunch(), "last"));
        Subscription after = subs.findById(s.getId()).orElseThrow();
        assertThat(after.getRemainingTokens()).isZero();
        assertThat(after.getStatus()).isEqualTo("EXHAUSTED");
        assertThatThrownBy(() -> redemption.redeem(cmd(w, c, w.dinner(), "after"))).hasMessage("error.sub.exhausted");
        assertThat(subs.findById(s.getId()).orElseThrow().getRemainingTokens()).isZero();
    }

    @Test
    void suspendedSubscriptionCannotRedeem() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planA());
        jdbc.update("UPDATE subscriptions SET status = 'SUSPENDED' WHERE id = ?", s.getId());
        assertThatThrownBy(() -> redemption.redeem(cmd(w, c, w.lunch(), "s"))).hasMessage("error.sub.suspended");
    }

    @Test
    void inactiveHotelCannotRedeem() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        fx.activeSubscription(w, c, w.planA());
        jdbc.update("UPDATE hotels SET status = 'SUSPENDED' WHERE id = ?", w.hotel().getId());
        assertThatThrownBy(() -> redemption.redeem(cmd(w, c, w.lunch(), "h"))).hasMessage("error.hotel.inactive");
    }

    @Test
    void reversalReturnsTheTokenFreesTheSlotIsIdempotentAndAudited() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planA());
        RedemptionResult r = redemption.redeem(cmd(w, c, w.lunch(), "r1"));

        assertThatThrownBy(() -> redemption.reverse(w.hotel().getId(), r.attendanceId(), w.owner().userId(), " "))
                .hasMessage("error.reason.required");

        redemption.reverse(w.hotel().getId(), r.attendanceId(), w.owner().userId(), "Scanned by mistake");
        Subscription after = subs.findById(s.getId()).orElseThrow();
        assertThat(after.getRemainingTokens()).isEqualTo(30);
        assertThat(after.getUsedTokens()).isZero();
        assertThat(attendance.findById(r.attendanceId()).orElseThrow().getStatus()).isEqualTo("REVERSED");
        assertThat(ledger.findBySubscriptionIdOrderByIdAsc(s.getId())).extracting(TokenTransaction::getTransactionType)
                .containsExactly("CREDIT", "DEBIT", "REVERSAL");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE hotel_id = ? AND action = 'MEAL_REVERSED'", Long.class, w.hotel().getId()))
                .isEqualTo(1L);

        // a second reversal must not refund again
        assertThatThrownBy(() -> redemption.reverse(w.hotel().getId(), r.attendanceId(), w.owner().userId(), "again"))
                .hasMessage("error.att.alreadyReversed");
        assertThat(subs.findById(s.getId()).orElseThrow().getRemainingTokens()).isEqualTo(30);

        // the freed slot can legitimately be served again
        redemption.redeem(cmd(w, c, w.lunch(), "r2"));
        assertThat(subs.findById(s.getId()).orElseThrow().getRemainingTokens()).isEqualTo(29);
    }

    @Test
    void reversalOfAnExhaustedSubscriptionReactivatesIt() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planA());
        jdbc.update("UPDATE subscriptions SET used_tokens = 29, remaining_tokens = 1 WHERE id = ?", s.getId());
        RedemptionResult r = redemption.redeem(cmd(w, c, w.lunch(), "e1"));
        assertThat(subs.findById(s.getId()).orElseThrow().getStatus()).isEqualTo("EXHAUSTED");
        redemption.reverse(w.hotel().getId(), r.attendanceId(), w.owner().userId(), "wrong person");
        Subscription after = subs.findById(s.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("ACTIVE");
        assertThat(after.getRemainingTokens()).isEqualTo(1);
    }

    @Test
    void ownerOfAnotherHotelCannotReverseThisHotelsAttendance() {
        World w = fx.newWorld();
        World other = fx.newWorld();
        Long c = fx.newCustomer();
        fx.activeSubscription(w, c, w.planA());
        RedemptionResult r = redemption.redeem(cmd(w, c, w.lunch(), "iso"));
        assertThatThrownBy(() -> redemption.reverse(other.hotel().getId(), r.attendanceId(), other.owner().userId(), "hack"))
                .hasMessage("error.notfound");
        assertThat(attendance.findById(r.attendanceId()).orElseThrow().getStatus()).isEqualTo("SERVED");
    }

    @Test
    void qrIdentifiersAreUniqueNonGuessableAndRevokedOnRotation() {
        World w = fx.newWorld();
        World other = fx.newWorld();
        HotelQrCode q1 = qr.ensureActive(w.hotel().getId());
        assertThat(q1.getPublicIdentifier()).hasSizeGreaterThanOrEqualTo(40).matches("[A-Za-z0-9_-]+");
        assertThat(qr.resolveHotel(q1.getPublicIdentifier()).getId()).isEqualTo(w.hotel().getId());
        assertThat(qr.ensureActive(other.hotel().getId()).getPublicIdentifier()).isNotEqualTo(q1.getPublicIdentifier());

        HotelQrCode q2 = qr.rotate(w.hotel().getId(), w.owner().userId());
        assertThat(q2.getPublicIdentifier()).isNotEqualTo(q1.getPublicIdentifier());
        assertThatThrownBy(() -> qr.resolveHotel(q1.getPublicIdentifier())).hasMessage("error.qr.invalid");
        assertThat(qr.resolveHotel(q2.getPublicIdentifier()).getId()).isEqualTo(w.hotel().getId());
        assertThat(qr.png(qr.urlFor(q2), 300)).hasSizeGreaterThan(200);
        assertThatThrownBy(() -> qr.resolveHotel("not-a-real-code")).hasMessage("error.qr.invalid");
    }
}
