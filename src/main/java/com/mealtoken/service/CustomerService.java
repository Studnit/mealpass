package com.mealtoken.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Read models for the customer UI. Every query is keyed by the signed-in customer's id. */
@Service
public class CustomerService {
    @lombok.Value
    public static class SlotView { Long id; String code; String name; LocalTime start; LocalTime end; boolean served; }

    @lombok.Value
    public static class SubView {
        Long id; Long hotelId; String hotelName; boolean hasLogo; String planName; int mealsPerDay; String status;
        String paymentStatus; LocalDate start; LocalDate expiry; int total; int used; int remaining; long daysLeft;
        List<SlotView> slots; int servedToday; boolean tokensExpire;
        public int getPercentUsed() { return total == 0 ? 0 : (int) Math.round(used * 100.0 / total); }
        public boolean isRedeemable() { return "ACTIVE".equals(status) && remaining > 0 && servedToday < mealsPerDay; }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public CustomerService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    /**
     * Convenience only (never enforced): which meal to pre-highlight at the hotel's local time.
     * 10:00-17:00 lunch, 18:00-24:00 dinner; nothing is suggested in the 17:00-18:00 gap or at night.
     */
    public String suggestedSlotCode(String hotelTimezone) {
        LocalTime t = LocalTime.now(clock.withZone(ZoneId.of(hotelTimezone)));
        if (!t.isBefore(LocalTime.of(10, 0)) && t.isBefore(LocalTime.of(17, 0))) return "LUNCH";
        if (!t.isBefore(LocalTime.of(18, 0))) return "DINNER";
        return null;
    }

    @Transactional(readOnly = true)
    public List<SubView> subscriptions(Long customerId, Long onlyHotelId) {
        String sql = "SELECT s.id, s.hotel_id, h.name AS hotel_name, h.timezone AS tz, (h.logo_path IS NOT NULL) AS has_logo, "
                + "sp.plan_name, sp.meals_per_day, sp.tokens_expire_with_subscription AS tokens_expire, s.status, s.payment_status, "
                + "s.start_date, s.expiry_date, s.total_tokens, s.used_tokens, s.remaining_tokens "
                + "FROM subscriptions s JOIN hotels h ON h.id = s.hotel_id JOIN subscription_plans sp ON sp.id = s.plan_id "
                + "WHERE s.customer_id = ? " + (onlyHotelId != null ? "AND s.hotel_id = ? " : "") + "ORDER BY s.created_at DESC, s.id DESC";
        List<Map<String, Object>> rows = onlyHotelId != null ? jdbc.queryForList(sql, customerId, onlyHotelId)
                : jdbc.queryForList(sql, customerId);
        List<SubView> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            ZoneId zone = ZoneId.of((String) r.get("tz"));
            LocalDate today = LocalDate.now(clock.withZone(zone));
            LocalDate start = r.get("start_date") == null ? null : ((Date) r.get("start_date")).toLocalDate();
            LocalDate expiry = r.get("expiry_date") == null ? null : ((Date) r.get("expiry_date")).toLocalDate();
            long left = expiry == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(today, expiry) + 1);
            String status = (String) r.get("status");
            Long sid = ((Number) r.get("id")).longValue();
            Long hid = ((Number) r.get("hotel_id")).longValue();
            List<SlotView> slots = List.of();
            int served = 0;
            if ("ACTIVE".equals(status)) {
                slots = jdbc.query(
                        "SELECT ms.id, ms.code, ms.name, ms.start_time, ms.end_time, EXISTS (SELECT 1 FROM meal_attendance a WHERE a.subscription_id = ? "
                                + "AND a.attendance_date = ? AND a.meal_slot_id = ms.id AND a.status = 'SERVED') AS served "
                                + "FROM meal_slots ms WHERE ms.hotel_id = ? AND ms.active ORDER BY ms.start_time",
                        (rs, i) -> new SlotView(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getTime("start_time").toLocalTime(),
                                rs.getTime("end_time").toLocalTime(), rs.getBoolean("served")),
                        sid, Date.valueOf(today), hid);
                served = (int) slots.stream().filter(SlotView::isServed).count();
            }
            out.add(new SubView(sid, hid, (String) r.get("hotel_name"), Boolean.TRUE.equals(r.get("has_logo")),
                    (String) r.get("plan_name"), ((Number) r.get("meals_per_day")).intValue(), status,
                    (String) r.get("payment_status"), start, expiry, ((Number) r.get("total_tokens")).intValue(),
                    ((Number) r.get("used_tokens")).intValue(), ((Number) r.get("remaining_tokens")).intValue(),
                    left, slots, served, Boolean.TRUE.equals(r.get("tokens_expire"))));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> recentAttendance(Long customerId, int limit) {
        return jdbc.queryForList(
                "SELECT h.name AS hotel, ms.name AS meal, a.reference_number AS ref, a.status AS status, a.attendance_date AS day, "
                        + "to_char(a.redeemed_at AT TIME ZONE h.timezone, 'HH24:MI')  AS time_at FROM meal_attendance a "
                        + "JOIN hotels h ON h.id = a.hotel_id JOIN meal_slots ms ON ms.id = a.meal_slot_id "
                        + "WHERE a.customer_id = ? ORDER BY a.id DESC LIMIT ?", customerId, limit);
    }

    /** Receipt data, looked up by reference AND customer id so references of other customers never resolve. */
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> receipt(Long customerId, String ref) {
        return jdbc.queryForList(
                "SELECT a.reference_number AS ref, h.name AS hotel, ms.name AS meal, a.attendance_date AS day, a.status AS status, "
                        + "to_char(a.redeemed_at AT TIME ZONE h.timezone, 'HH24:MI') AS time_at, s.remaining_tokens AS remaining "
                        + "FROM meal_attendance a JOIN hotels h ON h.id = a.hotel_id JOIN meal_slots ms ON ms.id = a.meal_slot_id "
                        + "JOIN subscriptions s ON s.id = a.subscription_id WHERE a.customer_id = ? AND a.reference_number = ?",
                customerId, ref).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeHotels(String q) {
        String like = "%" + (q == null ? "" : q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return jdbc.queryForList(
                "SELECT h.id, h.name, COALESCE(h.address, '') AS address, COALESCE(h.phone, '') AS phone, COALESCE(h.operating_hours, '') AS hours, "
                        + "(h.logo_path IS NOT NULL) AS has_logo FROM hotels h WHERE h.status = 'ACTIVE' "
                        + "AND (h.name ILIKE ? OR COALESCE(h.address, '') ILIKE ?) ORDER BY h.name LIMIT 100", like, like);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingPayments(Long customerId) {
        return jdbc.queryForList(
                "SELECT p.id, h.name AS hotel, p.amount, p.payment_method AS method, p.payment_reference AS reference "
                        + "FROM payments p JOIN hotels h ON h.id = p.hotel_id WHERE p.customer_id = ? AND p.status = 'PENDING' "
                        + "AND p.subscription_id IS NOT NULL ORDER BY p.id DESC", customerId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> myRequests(Long customerId) {
        return jdbc.queryForList(
                "SELECT r.id, h.name AS hotel, sp.plan_name AS plan, r.status, r.note FROM subscription_requests r "
                        + "JOIN hotels h ON h.id = r.hotel_id JOIN subscription_plans sp ON sp.id = r.plan_id "
                        + "WHERE r.customer_id = ? ORDER BY r.id DESC LIMIT 20", customerId);
    }
}
