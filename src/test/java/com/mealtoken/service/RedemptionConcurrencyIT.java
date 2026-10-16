package com.mealtoken.service;

import com.mealtoken.entity.MealSlot;
import com.mealtoken.entity.Subscription;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.SubscriptionRepository;
import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.Fixtures.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the row-lock + unique-index design under genuinely parallel requests. */
class RedemptionConcurrencyIT extends AbstractIntegrationTest {
    @Autowired Fixtures fx;
    @Autowired RedemptionService redemption;
    @Autowired SubscriptionRepository subs;
    @Autowired JdbcTemplate jdbc;

    /** Runs all tasks at the same instant; returns how many succeeded and the error keys of the rest. */
    private <T> Outcome runTogether(List<T> inputs, Function<T, RedemptionResult> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(inputs.size());
        CountDownLatch ready = new CountDownLatch(inputs.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (T in : inputs) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try { action.apply(in); return "OK"; }
                catch (BusinessException e) { return e.getMessage(); }
            }));
        }
        ready.await();
        go.countDown();
        AtomicInteger ok = new AtomicInteger();
        List<String> errors = new ArrayList<>();
        for (Future<String> f : futures) {
            String r = f.get(60, TimeUnit.SECONDS);
            if (r.equals("OK")) ok.incrementAndGet(); else errors.add(r);
        }
        pool.shutdownNow();
        return new Outcome(ok.get(), errors);
    }

    record Outcome(int succeeded, List<String> errors) {}

    @Test
    void twoDifferentMealsRacingForTheLastTokenConsumeItExactlyOnce() throws Exception {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planB());     // 2 meals/day, so the daily limit does not interfere
        jdbc.update("UPDATE subscriptions SET used_tokens = 59, remaining_tokens = 1 WHERE id = ?", s.getId());

        List<MealSlot> slots = List.of(w.lunch(), w.dinner());
        Outcome o = runTogether(slots, slot -> redemption.redeem(
                new RedeemCommand(c, w.hotel().getId(), slot.getId(), c, "k-" + slot.getId(), "TEST")));

        assertThat(o.succeeded()).isEqualTo(1);
        assertThat(o.errors()).containsExactly("error.sub.exhausted");
        Subscription after = subs.findById(s.getId()).orElseThrow();
        assertThat(after.getRemainingTokens()).isZero();
        assertThat(after.getUsedTokens()).isEqualTo(60);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_attendance WHERE subscription_id = ? AND status = 'SERVED'", Long.class, s.getId()))
                .isEqualTo(1L);
    }

    @Test
    void manyParallelScansOfTheSameMealCreateExactlyOneAttendance() throws Exception {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planA());

        List<Integer> clicks = List.of(1, 2, 3, 4, 5, 6, 7, 8);       // eight different idempotency keys: eight "different" requests
        Outcome o = runTogether(clicks, i -> redemption.redeem(
                new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, "click-" + i, "TEST")));

        assertThat(o.succeeded()).isEqualTo(1);
        assertThat(o.errors()).hasSize(7).allMatch(e -> e.equals("error.slot.duplicate") || e.equals("error.daily.limit"));
        assertThat(subs.findById(s.getId()).orElseThrow().getRemainingTokens()).isEqualTo(29);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM token_transactions WHERE subscription_id = ? AND transaction_type = 'DEBIT'", Long.class, s.getId()))
                .isEqualTo(1L);
    }

    @Test
    void doubleTapWithTheSameIdempotencyKeyDeductsOnce() throws Exception {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planA());

        // both requests succeed (the second one is a replay of the first receipt) but only one token is spent
        Outcome o = runTogether(List.of(1, 2, 3), i -> redemption.redeem(
                new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, "double-tap", "TEST")));

        assertThat(o.succeeded()).isEqualTo(3);
        assertThat(subs.findById(s.getId()).orElseThrow().getRemainingTokens()).isEqualTo(29);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM meal_attendance WHERE subscription_id = ?", Long.class, s.getId())).isEqualTo(1L);
    }

    @Test
    void databaseItselfRefusesDuplicateServedAttendance() {
        World w = fx.newWorld();
        Long c = fx.newCustomer();
        Subscription s = fx.activeSubscription(w, c, w.planA());
        redemption.redeem(new RedeemCommand(c, w.hotel().getId(), w.lunch().getId(), c, "db1", "TEST"));
        // bypass the service entirely: the partial unique index must still say no
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO meal_attendance (customer_id, hotel_id, subscription_id, meal_slot_id, attendance_date, reference_number, status) "
                        + "SELECT customer_id, hotel_id, subscription_id, meal_slot_id, attendance_date, 'MANUAL-DUP-1', 'SERVED' "
                        + "FROM meal_attendance WHERE subscription_id = ?", s.getId()))
                .hasMessageContaining("uq_active_attendance");
    }
}
