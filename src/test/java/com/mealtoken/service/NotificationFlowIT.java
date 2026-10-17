package com.mealtoken.service;

import com.mealtoken.entity.SubscriptionRequest;
import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.Fixtures.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationFlowIT extends AbstractIntegrationTest {
    @Autowired Fixtures fx;
    @Autowired SubscriptionService subscriptions;
    @Autowired RedemptionService redemption;
    @Autowired JdbcTemplate jdbc;

    private long count(Long user, String messageKey) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND message LIKE ?", Long.class, user, messageKey + "%");
    }

    @Test
    void submittedRequestNotifiesBothCustomerAndOwner() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        SubscriptionRequest r = subscriptions.request(c, w.hotel().getId(), w.planA().getId());
        assertThat(r.getId()).isNotNull();
        assertThat(count(c, "notif.req.submitted")).isEqualTo(1);
        assertThat(count(w.owner().userId(), "notif.req.new")).isEqualTo(1);
    }

    @Test
    void successfulMealScanNotifiesBothCustomerAndOwner() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        fx.activeSubscription(w, c, w.planA());
        redemption.redeem(new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, "n1", "TEST"));
        assertThat(count(c, "notif.redeem.ok")).isEqualTo(1);
        assertThat(count(w.owner().userId(), "notif.owner.meal")).isEqualTo(1);
    }

    @Test
    void rejectedScanDoesNotNotifyTheOwner() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();                                   // no subscription
        try { redemption.redeem(new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, "n2", "TEST")); } catch (RuntimeException ignored) { }
        assertThat(count(w.owner().userId(), "notif.owner.meal")).isZero();
    }
}
