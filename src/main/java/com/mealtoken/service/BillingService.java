package com.mealtoken.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/** Read models for the Billing screens. Customer queries are keyed by the customer, hotel queries by the server-resolved hotel. */
@Service
public class BillingService {
    private static final String PAID = "COALESCE((SELECT SUM(p.amount) FROM payments p WHERE p.subscription_id = s.id AND p.status = 'SUCCESS'), 0)";

    @lombok.Value
    public static class Line {
        Long subscriptionId; String party; String phone; String planName; String status;
        BigDecimal total; BigDecimal paid; Long pendingPaymentId; String pendingReference; int hold; int remainingTokens; int totalTokens;
        public BigDecimal getDue() { return total.subtract(paid).max(BigDecimal.ZERO); }
        public int getPercentPaid() { return total.signum() == 0 ? 100 : Math.min(100, paid.multiply(BigDecimal.valueOf(100)).divide(total, 0, java.math.RoundingMode.DOWN).intValue()); }
        public int getAlertLevel() { return RedemptionService.alertLevel(getDue(), hold, totalTokens, remainingTokens); }
        public int getTokensBeforeHold() { return Math.max(0, remainingTokens - getHeldTokens()); }
        public int getHeldTokens() { return getDue().signum() > 0 ? hold : 0; }
        public boolean isSettled() { return getDue().signum() == 0; }
    }

    @lombok.Value
    public static class Summary { BigDecimal billed; BigDecimal collected; BigDecimal outstanding; BigDecimal collectedToday; long openCount; }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public BillingService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    /** One line per subscription of this customer (cancelled ones that never received money are hidden). */
    @Transactional(readOnly = true)
    public List<Line> customerLines(Long customerId) {
        return jdbc.query("SELECT s.id, h.name AS party, '' AS phone, sp.plan_name, s.status, s.total_amount AS total, " + PAID + " AS paid, "
                        + pendingId() + " AS pending_id, " + pendingRef() + " AS pending_ref, h.token_hold_count AS hold, s.remaining_tokens AS rem_tokens, s.total_tokens AS tot_tokens "
                        + "FROM subscriptions s JOIN hotels h ON h.id = s.hotel_id JOIN subscription_plans sp ON sp.id = s.plan_id "
                        + "WHERE s.customer_id = ? AND (s.status <> 'CANCELLED' OR " + PAID + " > 0) ORDER BY s.id DESC",
                this::line, customerId);
    }

    /** Subscriptions of this hotel with money still owed first, then settled ones. */
    @Transactional(readOnly = true)
    public List<Line> hotelLines(Long hotelId) {
        return jdbc.query("SELECT s.id, u.full_name AS party, COALESCE(u.phone, '') AS phone, sp.plan_name, s.status, s.total_amount AS total, "
                        + PAID + " AS paid, " + pendingId() + " AS pending_id, " + pendingRef() + " AS pending_ref, h.token_hold_count AS hold, s.remaining_tokens AS rem_tokens, s.total_tokens AS tot_tokens "
                        + "FROM subscriptions s JOIN hotels h ON h.id = s.hotel_id JOIN users u ON u.id = s.customer_id JOIN subscription_plans sp ON sp.id = s.plan_id "
                        + "WHERE s.hotel_id = ? AND (s.status <> 'CANCELLED' OR " + PAID + " > 0) "
                        + "ORDER BY (s.total_amount - " + PAID + " > 0) DESC, s.id DESC LIMIT 300",
                this::line, hotelId);
    }

    @Transactional(readOnly = true)
    public Summary hotelSummary(Long hotelId, String timezone) {
        Map<String, Object> r = jdbc.queryForMap(
                "SELECT COALESCE(SUM(x.total), 0) AS billed, COALESCE(SUM(x.paid), 0) AS collected, "
                        + "COALESCE(SUM(GREATEST(x.total - x.paid, 0)), 0) AS due, COUNT(*) FILTER (WHERE x.total - x.paid > 0) AS open_count "
                        + "FROM (SELECT s.total_amount AS total, " + PAID + " AS paid FROM subscriptions s "
                        + "WHERE s.hotel_id = ? AND s.status <> 'CANCELLED') x", hotelId);
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(timezone)));
        BigDecimal todayTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM payments WHERE hotel_id = ? AND status = 'SUCCESS' "
                        + "AND CAST(paid_at AT TIME ZONE ? AS date) = ?", BigDecimal.class, hotelId, timezone, java.sql.Date.valueOf(today));
        return new Summary((BigDecimal) r.get("billed"), (BigDecimal) r.get("collected"), (BigDecimal) r.get("due"),
                todayTotal, ((Number) r.get("open_count")).longValue());
    }

    /** Receipts of this customer, newest first. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> customerReceipts(Long customerId, int limit) {
        return jdbc.queryForList(
                "SELECT p.id, COALESCE(p.receipt_number, '#' || p.id) AS receipt, to_char(p.paid_at AT TIME ZONE h.timezone, 'DD Mon YYYY HH24:MI') AS paid_on, "
                        + "h.name AS hotel, p.amount, p.payment_method AS method, COALESCE(p.payment_reference, '') AS reference "
                        + "FROM payments p JOIN hotels h ON h.id = p.hotel_id "
                        + "WHERE p.customer_id = ? AND p.status = 'SUCCESS' ORDER BY p.paid_at DESC, p.id DESC LIMIT " + Math.max(1, Math.min(limit, 100)),
                customerId);
    }

    /** Installment ledger (oldest first) for the given subscriptions: what was received, when, how, and by whom. */
    @Transactional(readOnly = true)
    public Map<Long, List<Map<String, Object>>> ledger(java.util.Collection<Long> subscriptionIds) {
        Map<Long, List<Map<String, Object>>> out = new java.util.LinkedHashMap<>();
        if (subscriptionIds.isEmpty()) return out;
        String in = String.join(",", java.util.Collections.nCopies(subscriptionIds.size(), "?"));
        jdbc.queryForList("SELECT p.subscription_id AS sid, p.amount, p.payment_method AS method, COALESCE(p.payment_reference, '') AS reference, "
                        + "p.status, COALESCE(p.receipt_number, '') AS receipt, COALESCE(v.full_name, '') AS received_by, "
                        + "COALESCE(to_char(p.paid_at AT TIME ZONE h.timezone, 'DD Mon YYYY HH24:MI'), '') AS paid_on "
                        + "FROM payments p JOIN hotels h ON h.id = p.hotel_id LEFT JOIN users v ON v.id = p.verified_by "
                        + "WHERE p.subscription_id IN (" + in + ") AND p.status IN ('SUCCESS','PENDING') ORDER BY p.id",
                subscriptionIds.toArray()).forEach(r -> out.computeIfAbsent(((Number) r.get("sid")).longValue(), k -> new java.util.ArrayList<>()).add(r));
        return out;
    }

    private static String pendingId() { return "(SELECT p.id FROM payments p WHERE p.subscription_id = s.id AND p.status = 'PENDING' ORDER BY p.id DESC LIMIT 1)"; }
    private static String pendingRef() { return "(SELECT p.payment_reference FROM payments p WHERE p.subscription_id = s.id AND p.status = 'PENDING' ORDER BY p.id DESC LIMIT 1)"; }

    private Line line(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        long pid = rs.getLong("pending_id");
        return new Line(rs.getLong("id"), rs.getString("party"), rs.getString("phone"), rs.getString("plan_name"), rs.getString("status"),
                rs.getBigDecimal("total"), rs.getBigDecimal("paid"), rs.wasNull() ? null : pid, rs.getString("pending_ref"), rs.getInt("hold"), rs.getInt("rem_tokens"), rs.getInt("tot_tokens"));
    }
}
