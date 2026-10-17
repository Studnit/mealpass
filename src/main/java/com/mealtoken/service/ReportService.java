package com.mealtoken.service;

import com.mealtoken.util.Fmt;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * One generic, safe query engine behind every list/report in the app: scoping (hotel or customer) is
 * injected by the server from the signed-in user, never from request parameters. Filters, search,
 * sorting and paging are parameterised or whitelisted. Dates are evaluated in the hotel's timezone.
 */
@Service
public class ReportService {
    public static final int PAGE_SIZE = 20;
    private static final int EXPORT_LIMIT = 10_000;

    public enum Scope { HOTEL, CUSTOMER }
    public enum Kind { TEXT, DATE, MONEY, NUM, STATUS }

    public record Col(String key, String sql, Kind kind) {
        String alias() { return "c_" + key; }
        // explicit getters: templates read bean-style properties
        public String getKey() { return key; }
        public Kind getKind() { return kind; }
    }

    public record Def(String type, String titleKey, Scope scope, String from, String where, String groupBy,
                      String idExpr, String dateExpr, String search, boolean defaultRecent, String statusKey,
                      List<String> statusOptions, String defaultSort, List<Col> cols) {
        public String getType() { return type; }
        public String getTitleKey() { return titleKey; }
        public String getStatusKey() { return statusKey; }
        public List<String> getStatusOptions() { return statusOptions; }
        public boolean isSearchable() { return search != null; }
        public boolean isDated() { return dateExpr != null; }
    }

    public record Query(String q, String status, LocalDate from, LocalDate to, String sort, boolean desc, int page) {
        public String getQ() { return q; }
        public String getStatus() { return status; }
        public LocalDate getFrom() { return from; }
        public LocalDate getTo() { return to; }
        public String getSort() { return sort; }
        public boolean isDesc() { return desc; }
        public int getPage() { return page; }
    }

    @lombok.Value
    public static class Cell { String text; String raw; Kind kind; }

    @lombok.Value
    public static class Row { long id; List<Cell> cells; }

    @lombok.Value
    public static class Result {
        Def def; List<Row> rows; int page; int pages; long total; Query query;
        public List<Col> getCols() { return def.cols(); }
    }

    private static final Map<String, Def> DEFS = new LinkedHashMap<>();

    private static Col c(String key, String sql, Kind k) { return new Col(key, sql, k); }
    private static void add(Def d) { DEFS.put(d.type(), d); }

    private static final String TZ_DATE_PAY = "CAST(p.created_at AT TIME ZONE :tz AS date)";

    static {
        // ------------------------------------------------------------ hotel-scoped (owner / staff)
        add(new Def("attendance", "nav.attendance", Scope.HOTEL,
                "meal_attendance a JOIN users u ON u.id = a.customer_id JOIN meal_slots ms ON ms.id = a.meal_slot_id "
                        + "LEFT JOIN users cb ON cb.id = a.created_by",
                "a.hotel_id = :hotelId", null, "a.id", "a.attendance_date",
                "u.full_name ILIKE :qq OR a.reference_number ILIKE :qq", true, "status",
                List.of("SERVED", "REVERSED"), "date", List.of(
                c("date", "a.attendance_date", Kind.DATE),
                c("time", "to_char(a.redeemed_at AT TIME ZONE :tz, 'HH24:MI')", Kind.TEXT),
                c("customer", "u.full_name", Kind.TEXT),
                c("meal", "ms.name", Kind.TEXT),
                c("status", "a.status", Kind.STATUS),
                c("ref", "a.reference_number", Kind.TEXT),
                c("by", "COALESCE(cb.full_name, '')", Kind.TEXT))));

        String ledgerFrom = "token_transactions t JOIN subscriptions s ON s.id = t.subscription_id JOIN users u ON u.id = s.customer_id";
        String ledgerDate = "CAST(t.created_at AT TIME ZONE :tz AS date)";
        List<Col> ledgerCols = List.of(
                c("date", ledgerDate, Kind.DATE),
                c("customer", "u.full_name", Kind.TEXT),
                c("type", "t.transaction_type", Kind.STATUS),
                c("amount", "t.amount", Kind.NUM),
                c("before", "t.balance_before", Kind.NUM),
                c("after", "t.balance_after", Kind.NUM),
                c("ref", "t.reference_number", Kind.TEXT),
                c("reason", "COALESCE(t.reason, '')", Kind.TEXT));
        String ledgerSearch = "u.full_name ILIKE :qq OR t.reference_number ILIKE :qq";
        add(new Def("ledger", "nav.ledger", Scope.HOTEL, ledgerFrom, "s.hotel_id = :hotelId", null, "t.id", ledgerDate,
                ledgerSearch, true, "type", List.of("CREDIT", "DEBIT", "REVERSAL", "EXPIRY", "ADJUSTMENT"), "date", ledgerCols));
        add(new Def("reversals", "report.reversals", Scope.HOTEL, ledgerFrom,
                "s.hotel_id = :hotelId AND t.transaction_type = 'REVERSAL'", null, "t.id", ledgerDate,
                ledgerSearch, true, null, List.of(), "date", ledgerCols));

        add(new Def("payments", "nav.payments", Scope.HOTEL,
                "payments p JOIN users u ON u.id = p.customer_id LEFT JOIN users v ON v.id = p.verified_by "
                        + "LEFT JOIN subscriptions s ON s.id = p.subscription_id LEFT JOIN subscription_plans sp ON sp.id = s.plan_id",
                "p.hotel_id = :hotelId", null, "p.id", TZ_DATE_PAY,
                "u.full_name ILIKE :qq OR COALESCE(p.payment_reference, '') ILIKE :qq OR COALESCE(p.receipt_number, '') ILIKE :qq",
                true, "status", List.of("PENDING", "SUCCESS", "FAILED", "REFUNDED", "CANCELLED"), "date", List.of(
                c("date", TZ_DATE_PAY, Kind.DATE),
                c("customer", "u.full_name", Kind.TEXT),
                c("plan", "COALESCE(sp.plan_name, '')", Kind.TEXT),
                c("amount", "p.amount", Kind.MONEY),
                c("method", "p.payment_method", Kind.STATUS),
                c("reference", "COALESCE(p.payment_reference, '')", Kind.TEXT),
                c("receipt", "COALESCE(p.receipt_number, '')", Kind.TEXT),
                c("status", "p.status", Kind.STATUS),
                c("verifier", "COALESCE(v.full_name, '')", Kind.TEXT))));

        String subFrom = "subscriptions s JOIN users u ON u.id = s.customer_id JOIN subscription_plans sp ON sp.id = s.plan_id";
        String subSearch = "u.full_name ILIKE :qq OR u.email ILIKE :qq OR COALESCE(u.phone, '') ILIKE :qq";
        String subDate = "CAST(s.created_at AT TIME ZONE :tz AS date)";
        add(new Def("subscriptions", "nav.customers", Scope.HOTEL, subFrom, "s.hotel_id = :hotelId", null, "s.id", subDate,
                subSearch, false, "status", List.of("PENDING", "ACTIVE", "EXPIRED", "SUSPENDED", "CANCELLED", "EXHAUSTED"),
                "customer", List.of(
                c("customer", "u.full_name", Kind.TEXT),
                c("phone", "COALESCE(u.phone, '')", Kind.TEXT),
                c("plan", "sp.plan_name", Kind.TEXT),
                c("start", "s.start_date", Kind.DATE),
                c("expiry", "s.expiry_date", Kind.DATE),
                c("total", "s.total_tokens", Kind.NUM),
                c("used", "s.used_tokens", Kind.NUM),
                c("remaining", "s.remaining_tokens", Kind.NUM),
                c("status", "s.status", Kind.STATUS),
                c("billed", "s.total_amount", Kind.MONEY),
                c("paid", "COALESCE((SELECT SUM(px.amount) FROM payments px WHERE px.subscription_id = s.id AND px.status = 'SUCCESS'), 0)", Kind.MONEY),
                c("due", "GREATEST(s.total_amount - COALESCE((SELECT SUM(px.amount) FROM payments px WHERE px.subscription_id = s.id AND px.status = 'SUCCESS'), 0), 0)", Kind.MONEY),
                c("paystatus", "s.payment_status", Kind.STATUS),
                c("kind", "CASE WHEN EXISTS (SELECT 1 FROM subscriptions p2 WHERE p2.customer_id = s.customer_id "
                        + "AND p2.hotel_id = s.hotel_id AND p2.id < s.id AND p2.payment_status = 'SUCCESS') "
                        + "THEN 'RENEWAL' ELSE 'NEW' END", Kind.STATUS))));

        add(new Def("unused", "report.unused", Scope.HOTEL, subFrom,
                "s.hotel_id = :hotelId AND s.remaining_tokens > 0 AND s.status IN ('ACTIVE','EXPIRED','SUSPENDED')",
                null, "s.id", subDate, subSearch, false, "status", List.of("ACTIVE", "EXPIRED", "SUSPENDED"), "remaining", List.of(
                c("customer", "u.full_name", Kind.TEXT),
                c("plan", "sp.plan_name", Kind.TEXT),
                c("expiry", "s.expiry_date", Kind.DATE),
                c("remaining", "s.remaining_tokens", Kind.NUM),
                c("status", "s.status", Kind.STATUS))));

        String revDate = "CAST(p.paid_at AT TIME ZONE :tz AS date)";
        add(new Def("revenue", "report.revenue", Scope.HOTEL, "payments p",
                "p.hotel_id = :hotelId AND p.status = 'SUCCESS' AND p.paid_at IS NOT NULL", revDate, "MAX(p.id)", revDate,
                null, true, null, List.of(), "date", List.of(
                c("date", revDate, Kind.DATE),
                c("count", "COUNT(*)", Kind.NUM),
                c("total", "SUM(p.amount)", Kind.MONEY))));

        add(new Def("mealcounts", "report.mealcounts", Scope.HOTEL,
                "meal_attendance a JOIN meal_slots ms ON ms.id = a.meal_slot_id",
                "a.hotel_id = :hotelId AND a.status = 'SERVED'", "a.attendance_date, ms.name", "MAX(a.id)", "a.attendance_date",
                null, true, null, List.of(), "date", List.of(
                c("date", "a.attendance_date", Kind.DATE),
                c("meal", "ms.name", Kind.TEXT),
                c("count", "COUNT(*)", Kind.NUM))));

        add(new Def("staff", "report.staff", Scope.HOTEL,
                "meal_attendance a JOIN users cb ON cb.id = a.created_by JOIN hotel_staff hs ON hs.user_id = cb.id AND hs.hotel_id = a.hotel_id",
                "a.hotel_id = :hotelId AND a.status = 'SERVED'", "a.attendance_date, cb.full_name", "MAX(a.id)", "a.attendance_date",
                "cb.full_name ILIKE :qq", true, null, List.of(), "date", List.of(
                c("date", "a.attendance_date", Kind.DATE),
                c("staff", "cb.full_name", Kind.TEXT),
                c("count", "COUNT(*)", Kind.NUM))));

        String auditDate = "CAST(l.created_at AT TIME ZONE :tz AS date)";
        add(new Def("audit", "nav.audit", Scope.HOTEL, "audit_logs l LEFT JOIN users u ON u.id = l.actor_user_id",
                "l.hotel_id = :hotelId", null, "l.id", auditDate,
                "COALESCE(u.full_name, '') ILIKE :qq OR l.action ILIKE :qq OR COALESCE(l.details, '') ILIKE :qq",
                true, null, List.of(), "when", List.of(
                c("when", "to_char(l.created_at AT TIME ZONE :tz, 'YYYY-MM-DD HH24:MI')", Kind.TEXT),
                c("actor", "COALESCE(u.full_name, 'system')", Kind.TEXT),
                c("action", "l.action", Kind.TEXT),
                c("entity", "COALESCE(l.entity_type, '')", Kind.TEXT),
                c("entityid", "COALESCE(CAST(l.entity_id AS text), '')", Kind.TEXT),
                c("details", "COALESCE(l.details, '')", Kind.TEXT))));

        // ------------------------------------------------------------ customer-scoped
        add(new Def("my_attendance", "nav.c_attendance", Scope.CUSTOMER,
                "meal_attendance a JOIN hotels h ON h.id = a.hotel_id JOIN meal_slots ms ON ms.id = a.meal_slot_id",
                "a.customer_id = :customerId", null, "a.id", "a.attendance_date",
                "h.name ILIKE :qq OR a.reference_number ILIKE :qq", false, "status", List.of("SERVED", "REVERSED"), "date", List.of(
                c("date", "a.attendance_date", Kind.DATE),
                c("time", "to_char(a.redeemed_at AT TIME ZONE h.timezone, 'HH24:MI')", Kind.TEXT),
                c("hotel", "h.name", Kind.TEXT),
                c("meal", "ms.name", Kind.TEXT),
                c("status", "a.status", Kind.STATUS),
                c("ref", "a.reference_number", Kind.TEXT))));

        String myTxDate = "CAST(t.created_at AT TIME ZONE h.timezone AS date)";
        add(new Def("my_tokens", "nav.c_tokens", Scope.CUSTOMER,
                "token_transactions t JOIN subscriptions s ON s.id = t.subscription_id JOIN hotels h ON h.id = s.hotel_id",
                "s.customer_id = :customerId", null, "t.id", myTxDate,
                "h.name ILIKE :qq OR t.reference_number ILIKE :qq", false, "type",
                List.of("CREDIT", "DEBIT", "REVERSAL", "EXPIRY", "ADJUSTMENT"), "id", List.of(
                c("id", "t.id", Kind.NUM),
                c("date", myTxDate, Kind.DATE),
                c("hotel", "h.name", Kind.TEXT),
                c("type", "t.transaction_type", Kind.STATUS),
                c("amount", "t.amount", Kind.NUM),
                c("before", "t.balance_before", Kind.NUM),
                c("after", "t.balance_after", Kind.NUM),
                c("ref", "t.reference_number", Kind.TEXT),
                c("reason", "COALESCE(t.reason, '')", Kind.TEXT))));

        String myPayDate = "CAST(p.created_at AT TIME ZONE h.timezone AS date)";
        add(new Def("my_payments", "nav.c_payments", Scope.CUSTOMER, "payments p JOIN hotels h ON h.id = p.hotel_id",
                "p.customer_id = :customerId", null, "p.id", myPayDate,
                "h.name ILIKE :qq OR COALESCE(p.payment_reference, '') ILIKE :qq OR COALESCE(p.receipt_number, '') ILIKE :qq",
                false, "status", List.of("PENDING", "SUCCESS", "FAILED", "REFUNDED", "CANCELLED"), "date", List.of(
                c("date", myPayDate, Kind.DATE),
                c("hotel", "h.name", Kind.TEXT),
                c("amount", "p.amount", Kind.MONEY),
                c("method", "p.payment_method", Kind.STATUS),
                c("reference", "COALESCE(p.payment_reference, '')", Kind.TEXT),
                c("receipt", "COALESCE(p.receipt_number, '')", Kind.TEXT),
                c("status", "p.status", Kind.STATUS))));
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final Fmt fmt;
    private final Clock clock;

    public ReportService(NamedParameterJdbcTemplate jdbc, Fmt fmt, Clock clock) {
        this.jdbc = jdbc; this.fmt = fmt; this.clock = clock;
    }

    public static Optional<Def> def(String type) { return Optional.ofNullable(DEFS.get(type)); }

    /** Report types that appear on the owner's Reports page. */
    public static List<Def> ownerReports() {
        return DEFS.values().stream().filter(d -> d.scope() == Scope.HOTEL).toList();
    }

    @Transactional(readOnly = true)
    public Result run(Def d, long scopeId, String tz, Query q, boolean paged) {
        String zone = safeZone(tz);
        Map<String, Object> p = new HashMap<>();
        p.put(d.scope() == Scope.HOTEL ? "hotelId" : "customerId", scopeId);

        String search = q.q() == null ? "" : q.q().trim();
        boolean searched = !search.isEmpty() && d.search() != null;
        if (searched) p.put("qq", "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");

        LocalDate from = q.from(), to = q.to();
        if (d.dateExpr() != null && d.defaultRecent() && from == null && to == null) {
            to = LocalDate.now(clock.withZone(ZoneId.of(zone)));
            from = to.minusDays(29);
        }

        StringBuilder in = new StringBuilder("SELECT ").append(d.idExpr()).append(" AS row_id");
        for (Col c : d.cols()) in.append(", ").append(c.sql()).append(" AS ").append(c.alias());
        in.append(" FROM ").append(d.from()).append(" WHERE ").append(d.where());
        if (d.dateExpr() != null && from != null) { in.append(" AND ").append(d.dateExpr()).append(" >= :from"); p.put("from", from); }
        if (d.dateExpr() != null && to != null)   { in.append(" AND ").append(d.dateExpr()).append(" <= :to");   p.put("to", to); }
        if (searched) in.append(" AND (").append(d.search()).append(")");
        if (d.groupBy() != null) in.append(" GROUP BY ").append(d.groupBy());

        StringBuilder filter = new StringBuilder();
        String status = q.status() == null ? "" : q.status().trim();
        if (d.statusKey() != null && d.statusOptions().contains(status)) {
            filter.append(" WHERE t.c_").append(d.statusKey()).append(" = :status");
            p.put("status", status);
        }

        String sortKey = d.cols().stream().map(Col::key).filter(k -> k.equals(q.sort())).findFirst().orElse(d.defaultSort());
        boolean desc = q.sort() == null || d.cols().stream().noneMatch(c -> c.key().equals(q.sort())) || q.desc();
        String order = " ORDER BY t.c_" + sortKey + (desc ? " DESC" : " ASC") + " NULLS LAST, t.row_id DESC";

        String base = "SELECT * FROM (" + in + ") t" + filter;
        long total = paged ? count(base, p, zone) : 0;
        int page = Math.max(q.page(), 0);
        int pages = paged ? (int) Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE) : 1;
        if (page >= pages) page = pages - 1;

        String sql = base + order + (paged ? " LIMIT " + PAGE_SIZE + " OFFSET " + ((long) page * PAGE_SIZE) : " LIMIT " + EXPORT_LIMIT);
        List<Map<String, Object>> raw = jdbc.queryForList(inline(sql, zone), p);

        List<Row> rows = new ArrayList<>(raw.size());
        for (Map<String, Object> r : raw) {
            List<Cell> cells = new ArrayList<>(d.cols().size());
            for (Col c : d.cols()) cells.add(cell(c, r.get(c.alias())));
            rows.add(new Row(((Number) r.get("row_id")).longValue(), cells));
        }
        return new Result(d, rows, page, pages, paged ? total : rows.size(), new Query(search, status, from, to, sortKey, desc, page));
    }

    private long count(String base, Map<String, Object> p, String zone) {
        Long n = jdbc.queryForObject(inline("SELECT count(*) FROM (" + base + ") x", zone), p, Long.class);
        return n == null ? 0 : n;
    }

    private Cell cell(Col c, Object v) {
        if (v == null) return new Cell("", "", c.kind());
        switch (c.kind()) {
            case DATE: {
                LocalDate d = v instanceof Date sd ? sd.toLocalDate() : (LocalDate) v;
                return new Cell(fmt.date(d), d.toString(), c.kind());
            }
            case MONEY: {
                BigDecimal b = v instanceof BigDecimal bd ? bd : new BigDecimal(v.toString());
                return new Cell(fmt.money(b), b.toPlainString(), c.kind());
            }
            default:
                return new Cell(v.toString(), v.toString(), c.kind());
        }
    }

    /** Timezone is validated as a real zone id, then inlined as a literal so GROUP BY expressions match SELECT ones. */
    private static String safeZone(String tz) {
        try { return ZoneId.of(tz == null ? "Asia/Kolkata" : tz).getId(); } catch (Exception e) { return "Asia/Kolkata"; }
    }

    private static String inline(String sql, String zone) {
        if (!zone.matches("[A-Za-z0-9_/+\\-]+")) zone = "Asia/Kolkata";
        return sql.replace(":tz", "'" + zone + "'");
    }

    // ------------------------------------------------------------------ CSV export (Excel-compatible)

    public String csv(Result r) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", r.getCols().stream().map(c -> esc(c.key())).toList())).append("\r\n");
        for (Row row : r.getRows()) {
            List<String> out = new ArrayList<>();
            for (Cell c : row.getCells())
                out.add(esc(c.getKind() == Kind.TEXT || c.getKind() == Kind.STATUS ? c.getText() : c.getRaw()));
            sb.append(String.join(",", out)).append("\r\n");
        }
        return sb.toString();
    }

    /** Quotes the field and neutralises spreadsheet formula injection (=, +, -, @ prefixes). */
    static String esc(String s) {
        if (s == null) s = "";
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
