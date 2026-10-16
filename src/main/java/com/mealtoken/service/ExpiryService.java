package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.Hotel;
import com.mealtoken.entity.Subscription;
import com.mealtoken.entity.SubscriptionPlan;
import com.mealtoken.notification.NotificationService;
import com.mealtoken.repository.*;
import com.mealtoken.util.Refs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Hourly job (each hotel judged in its own timezone): expires lapsed subscriptions and warns about
 * those about to lapse. Redemption itself also re-checks the expiry date, so correctness never depends on this job.
 */
@Service
public class ExpiryService {
    private static final Logger log = LoggerFactory.getLogger(ExpiryService.class);
    private static final int WARN_DAYS = 3;

    private final HotelRepository hotels;
    private final SubscriptionRepository subs;
    private final SubscriptionPlanRepository plans;
    private final TokenTransactionRepository ledger;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Clock clock;
    private final TransactionTemplate tx;

    public ExpiryService(HotelRepository hotels, SubscriptionRepository subs, SubscriptionPlanRepository plans,
                         TokenTransactionRepository ledger, AuditService audit, NotificationService notifications,
                         Clock clock, TransactionTemplate tx) {
        this.hotels = hotels; this.subs = subs; this.plans = plans; this.ledger = ledger; this.audit = audit;
        this.notifications = notifications; this.clock = clock; this.tx = tx;
    }

    @Scheduled(cron = "0 10 * * * *")
    public void scheduled() {
        try { process(); } catch (RuntimeException e) { log.error("Expiry processing failed", e); }
    }

    /** @return number of subscriptions expired in this run. */
    public int process() {
        int expired = 0;
        for (Hotel h : hotels.findByStatus("ACTIVE")) {
            LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(h.getTimezone())));
            for (Subscription s : subs.findByHotelIdAndStatusAndExpiryDateLessThan(h.getId(), "ACTIVE", today)) {
                Boolean done = tx.execute(st -> expire(s.getId(), h, today));
                if (Boolean.TRUE.equals(done)) expired++;
            }
            tx.executeWithoutResult(st -> {
                for (Subscription s : subs.findByHotelIdAndStatusAndExpiryDate(h.getId(), "ACTIVE", today.plusDays(WARN_DAYS)))
                    notifications.notifyOnce(s.getCustomerId(), "EXPIRING", "notif.sub.expiring", h.getName(), s.getExpiryDate());
            });
        }
        return expired;
    }

    private boolean expire(Long subId, Hotel h, LocalDate today) {
        Subscription s = subs.lockById(subId).orElse(null);
        if (s == null || !"ACTIVE".equals(s.getStatus()) || !s.getExpiryDate().isBefore(today)) return false;
        SubscriptionPlan plan = plans.findById(s.getPlanId()).orElseThrow();
        s.setStatus("EXPIRED");
        subs.saveAndFlush(s);
        if (s.getRemainingTokens() > 0) {
            // informational ledger row: the balance itself is never silently rewritten
            String reason = plan.isTokensExpireWithSubscription()
                    ? "Subscription expired; unused tokens forfeited"
                    : "Subscription expired; unused tokens kept for owner-authorised extension";
            ledger.save(RedemptionService.entry(s.getId(), "EXPIRY", s.getRemainingTokens(),
                    s.getRemainingTokens(), s.getRemainingTokens(), Refs.next("EX"), reason, null));
        }
        audit.log(null, h.getId(), "SUBSCRIPTION_EXPIRED", "Subscription", s.getId(), "unused=" + s.getRemainingTokens());
        notifications.notify(s.getCustomerId(), "EXPIRED", "notif.sub.expired", h.getName());
        return true;
    }
}
