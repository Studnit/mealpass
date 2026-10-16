package com.mealtoken.service;

import com.mealtoken.security.HotelContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** Aggregated statistics and chart series, always computed from persisted rows in the hotel's timezone. */
@Service
public class DashboardService {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DashboardService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> owner(HotelContext ctx, LocalDate from, LocalDate to) {
        long h = ctx.hotelId();
        ZoneId zone = ZoneId.of(ctx.hotel().getTimezone());
        String tz = zone.getId();
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (to == null) to = today;
        if (from == null) from = to.minusDays(29);
        if (from.isAfter(to)) from = to;
        int low = ctx.hotel().getLowTokenThreshold();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", from);
        m.put("to", to);
        long todayTotal = count("SELECT count(*) FROM meal_attendance WHERE hotel_id = ? AND attendance_date = ? AND status = 'SERVED'", h, Date.valueOf(today));
        m.put("todayTotal", todayTotal);
        m.put("todaySlots", jdbc.queryForList(
                "SELECT ms.name AS name, count(a.id) AS n FROM meal_slots ms LEFT JOIN meal_attendance a "
                        + "ON a.meal_slot_id = ms.id AND a.attendance_date = ? AND a.status = 'SERVED' "
                        + "WHERE ms.hotel_id = ? AND ms.active GROUP BY ms.id, ms.name, ms.start_time ORDER BY ms.start_time",
                Date.valueOf(today), h));
        m.put("activeSubscribers", count("SELECT count(*) FROM subscriptions WHERE hotel_id = ? AND status = 'ACTIVE'", h));
        m.put("pendingRequests", count("SELECT count(*) FROM subscription_requests WHERE hotel_id = ? AND status = 'PENDING'", h));
        m.put("pendingPayments", count("SELECT count(*) FROM payments WHERE hotel_id = ? AND status = 'PENDING' AND subscription_id IS NOT NULL", h));
        m.put("expiringSoon", count("SELECT count(*) FROM subscriptions WHERE hotel_id = ? AND status = 'ACTIVE' AND expiry_date BETWEEN ? AND ?",
                h, Date.valueOf(today), Date.valueOf(today.plusDays(3))));
        m.put("lowTokens", count("SELECT count(*) FROM subscriptions WHERE hotel_id = ? AND status = 'ACTIVE' AND remaining_tokens <= ?", h, low));
        m.put("tokensToday", todayTotal);
        BigDecimal revenue = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE hotel_id = ? AND status = 'SUCCESS' AND paid_at IS NOT NULL "
                        + "AND CAST(paid_at AT TIME ZONE '" + tz + "' AS date) BETWEEN ? AND ?",
                BigDecimal.class, h, Date.valueOf(from), Date.valueOf(to));
        m.put("revenue", revenue);
        m.put("recent", jdbc.queryForList(
                "SELECT u.full_name AS customer, ms.name AS meal, a.reference_number AS ref, a.status AS status, "
                        + "to_char(a.redeemed_at AT TIME ZONE '" + tz + "', 'DD Mon HH24:MI')  AS time_at "
                        + "FROM meal_attendance a JOIN users u ON u.id = a.customer_id JOIN meal_slots ms ON ms.id = a.meal_slot_id "
                        + "WHERE a.hotel_id = ? ORDER BY a.id DESC LIMIT 8", h));
        m.put("charts", charts(h, tz, today, from, to));
        return m;
    }

    private Map<String, Object> charts(long h, String tz, LocalDate today, LocalDate from, LocalDate to) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("daily", series(
                "SELECT to_char(d, 'DD Mon') AS label, COALESCE(x.n, 0) AS v FROM generate_series(CAST(? AS date), CAST(? AS date), interval '1 day') d "
                        + "LEFT JOIN (SELECT attendance_date dt, count(*) n FROM meal_attendance WHERE hotel_id = ? AND status = 'SERVED' GROUP BY 1) x "
                        + "ON x.dt = CAST(d AS date) ORDER BY d",
                Date.valueOf(today.minusDays(13)), Date.valueOf(today), h));
        c.put("weekly", series(
                "SELECT to_char(date_trunc('week', attendance_date), 'DD Mon') AS label, count(*) AS v FROM meal_attendance "
                        + "WHERE hotel_id = ? AND status = 'SERVED' AND attendance_date >= ? GROUP BY date_trunc('week', attendance_date) "
                        + "ORDER BY date_trunc('week', attendance_date)",
                h, Date.valueOf(today.minusWeeks(8))));
        c.put("revenue", series(
                "SELECT to_char(d, 'DD Mon') AS label, COALESCE(x.s, 0) AS v FROM generate_series(CAST(? AS date), CAST(? AS date), interval '1 day') d "
                        + "LEFT JOIN (SELECT CAST(paid_at AT TIME ZONE '" + tz + "' AS date) dt, SUM(amount) s FROM payments "
                        + "WHERE hotel_id = ? AND status = 'SUCCESS' AND paid_at IS NOT NULL GROUP BY 1) x ON x.dt = CAST(d AS date) ORDER BY d",
                Date.valueOf(from), Date.valueOf(to), h));
        c.put("slots", series(
                "SELECT ms.name AS label, count(a.id) AS v FROM meal_slots ms LEFT JOIN meal_attendance a ON a.meal_slot_id = ms.id AND a.status = 'SERVED' "
                        + "WHERE ms.hotel_id = ? GROUP BY ms.id, ms.name ORDER BY ms.start_time", h));
        c.put("status", series(
                "SELECT status AS label, count(*) AS v FROM subscriptions WHERE hotel_id = ? GROUP BY status ORDER BY status", h));

        List<Map<String, Object>> nr = jdbc.queryForList(
                "SELECT to_char(date_trunc('month', s.created_at AT TIME ZONE '" + tz + "'), 'Mon YYYY') AS label, "
                        + "date_trunc('month', s.created_at AT TIME ZONE '" + tz + "') AS mth, "
                        + "SUM(CASE WHEN EXISTS (SELECT 1 FROM subscriptions p2 WHERE p2.customer_id = s.customer_id AND p2.hotel_id = s.hotel_id "
                        + "AND p2.id < s.id AND p2.payment_status = 'SUCCESS') THEN 0 ELSE 1 END) AS newer, "
                        + "SUM(CASE WHEN EXISTS (SELECT 1 FROM subscriptions p2 WHERE p2.customer_id = s.customer_id AND p2.hotel_id = s.hotel_id "
                        + "AND p2.id < s.id AND p2.payment_status = 'SUCCESS') THEN 1 ELSE 0 END) AS renewed "
                        + "FROM subscriptions s WHERE s.hotel_id = ? AND s.payment_status = 'SUCCESS' AND s.created_at >= now() - interval '6 months' "
                        + "GROUP BY 1, 2 ORDER BY 2", h);
        Map<String, Object> nrm = new LinkedHashMap<>();
        nrm.put("labels", nr.stream().map(r -> String.valueOf(r.get("label"))).toList());
        nrm.put("newer", nr.stream().map(r -> ((Number) r.get("newer")).longValue()).toList());
        nrm.put("renewed", nr.stream().map(r -> ((Number) r.get("renewed")).longValue()).toList());
        c.put("newVsRenewed", nrm);
        return c;
    }

    private Map<String, Object> series(String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("labels", rows.stream().map(r -> String.valueOf(r.get("label"))).toList());
        s.put("values", rows.stream().map(r -> ((Number) r.get("v")).doubleValue()).toList());
        return s;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> admin() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalHotels", count("SELECT count(*) FROM hotels"));
        m.put("activeHotels", count("SELECT count(*) FROM hotels WHERE status = 'ACTIVE'"));
        m.put("pendingHotels", count("SELECT count(*) FROM hotels WHERE status = 'PENDING'"));
        m.put("suspendedHotels", count("SELECT count(*) FROM hotels WHERE status = 'SUSPENDED'"));
        m.put("totalCustomers", count("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id WHERE r.name = 'CUSTOMER'"));
        m.put("activeSubscriptions", count("SELECT count(*) FROM subscriptions WHERE status = 'ACTIVE'"));
        m.put("paidTransactions", count("SELECT count(*) FROM payments WHERE status = 'SUCCESS'"));
        m.put("pendingPayments", count("SELECT count(*) FROM payments WHERE status = 'PENDING'"));
        m.put("totalRevenue", jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status = 'SUCCESS'", BigDecimal.class));
        m.put("mealsToday", count("SELECT count(*) FROM meal_attendance WHERE status = 'SERVED' AND redeemed_at >= now() - interval '24 hours'"));
        m.put("subsByStatus", series("SELECT status AS label, count(*) AS v FROM subscriptions GROUP BY status ORDER BY status"));
        return m;
    }
}
