package com.mealtoken.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Small owner/staff read queries that are not tabular reports. All are keyed by the server-resolved hotel id. */
@Service
public class OwnerQueryService {
    private final JdbcTemplate jdbc;

    public OwnerQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingRequests(Long hotelId) {
        return jdbc.queryForList(
                "SELECT r.id, u.full_name AS customer, COALESCE(u.phone, '') AS phone, sp.plan_name AS plan, sp.price AS price, "
                        + "sp.total_tokens AS tokens, to_char(r.created_at, 'DD Mon YYYY') AS requested FROM subscription_requests r "
                        + "JOIN users u ON u.id = r.customer_id JOIN subscription_plans sp ON sp.id = r.plan_id "
                        + "WHERE r.hotel_id = ? AND r.status = 'PENDING' ORDER BY r.id", hotelId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> pendingPayments(Long hotelId) {
        return jdbc.queryForList(
                "SELECT p.id, u.full_name AS customer, p.amount, p.payment_method AS method, COALESCE(p.payment_reference, '') AS reference, "
                        + "s.status AS sub_status, s.total_amount AS total, "
                        + "COALESCE((SELECT SUM(x.amount) FROM payments x WHERE x.subscription_id = s.id AND x.status = 'SUCCESS'), 0) AS paid "
                        + "FROM payments p JOIN users u ON u.id = p.customer_id JOIN subscriptions s ON s.id = p.subscription_id "
                        + "WHERE p.hotel_id = ? AND p.status = 'PENDING' "
                        + "AND p.subscription_id IS NOT NULL ORDER BY p.id", hotelId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> staff(Long hotelId) {
        return jdbc.queryForList(
                "SELECT hs.id, u.full_name AS name, u.email, COALESCE(u.phone, '') AS phone, hs.active, hs.can_confirm_payments AS can_confirm "
                        + "FROM hotel_staff hs JOIN users u ON u.id = hs.user_id WHERE hs.hotel_id = ? ORDER BY u.full_name", hotelId);
    }

    /** Customer's subscription at this hotel only; nothing about other hotels is ever exposed to staff. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> customerAtHotel(Long customerId, Long hotelId) {
        return jdbc.queryForList(
                "SELECT u.full_name AS name, COALESCE(u.phone, '') AS phone, s.status, sp.plan_name AS plan, s.remaining_tokens AS remaining, "
                        + "s.total_tokens AS total, s.expiry_date AS expiry FROM users u LEFT JOIN subscriptions s ON s.customer_id = u.id AND s.hotel_id = ? "
                        + "AND s.status = 'ACTIVE' LEFT JOIN subscription_plans sp ON sp.id = s.plan_id WHERE u.id = ?", hotelId, customerId);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<Long> findCustomerId(String emailOrPhone) {
        String v = emailOrPhone == null ? "" : emailOrPhone.trim();
        if (v.isEmpty()) return java.util.Optional.empty();
        List<Long> ids = jdbc.queryForList(
                "SELECT u.id FROM users u JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id "
                        + "WHERE r.name = 'CUSTOMER' AND u.status = 'ACTIVE' AND (lower(u.email) = lower(?) OR u.phone = ?) LIMIT 2", Long.class, v, v);
        return ids.size() == 1 ? java.util.Optional.of(ids.get(0)) : java.util.Optional.empty();
    }
}
