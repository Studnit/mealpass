package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.ApplicationSetting;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.ApplicationSettingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AdminService {
    private final JdbcTemplate jdbc;
    private final ApplicationSettingRepository settings;
    private final AuditService audit;

    public AdminService(JdbcTemplate jdbc, ApplicationSettingRepository settings, AuditService audit) {
        this.jdbc = jdbc; this.settings = settings; this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> hotels(String status) {
        String where = status == null || status.isBlank() ? "" : "WHERE h.status = ? ";
        String sql = "SELECT h.id, h.name, h.status, h.timezone, u.full_name AS owner, u.email AS owner_email, "
                + "(SELECT count(*) FROM subscriptions s WHERE s.hotel_id = h.id AND s.status = 'ACTIVE') AS active_subs, "
                + "to_char(h.created_at, 'YYYY-MM-DD') AS created FROM hotels h JOIN users u ON u.id = h.owner_id " + where + "ORDER BY h.created_at DESC LIMIT 200";
        return where.isEmpty() ? jdbc.queryForList(sql) : jdbc.queryForList(sql, status);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> users(String q) {
        String like = "%" + (q == null ? "" : q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return jdbc.queryForList(
                "SELECT u.id, u.full_name AS name, u.email, u.status, string_agg(r.name, ', ') AS roles FROM users u "
                        + "JOIN user_roles ur ON ur.user_id = u.id JOIN roles r ON r.id = ur.role_id "
                        + "WHERE u.full_name ILIKE ? OR u.email ILIKE ? GROUP BY u.id ORDER BY u.id DESC LIMIT 200", like, like);
    }

    /** Admins may suspend/reactivate owners, staff and customers, never other Super Admins or themselves. */
    @Transactional
    public void setUserStatus(Long adminId, Long userId, String status) {
        if (!Set.of("ACTIVE", "SUSPENDED").contains(status)) throw new BusinessException("error.input.invalid");
        if (adminId.equals(userId)) throw new BusinessException("error.forbidden");
        Integer admins = jdbc.queryForObject("SELECT count(*) FROM user_roles ur JOIN roles r ON r.id = ur.role_id "
                + "WHERE ur.user_id = ? AND r.name = 'SUPER_ADMIN'", Integer.class, userId);
        if (admins != null && admins > 0) throw new BusinessException("error.forbidden");
        int n = jdbc.update("UPDATE users SET status = ?, updated_at = now() WHERE id = ?", status, userId);
        if (n == 0) throw new BusinessException("error.notfound");
        audit.log(adminId, null, "USER_" + status, "User", userId, null);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> platformAudit(int page) {
        return jdbc.queryForList(
                "SELECT to_char(l.created_at, 'YYYY-MM-DD HH24:MI') AS time_at, COALESCE(u.full_name, 'system') AS actor, l.action, "
                        + "COALESCE(h.name, '') AS hotel, COALESCE(l.entity_type, '') AS entity, COALESCE(l.details, '') AS details "
                        + "FROM audit_logs l LEFT JOIN users u ON u.id = l.actor_user_id LEFT JOIN hotels h ON h.id = l.hotel_id "
                        + "ORDER BY l.id DESC LIMIT 50 OFFSET ?", Math.max(page, 0) * 50);
    }

    /** Heuristic for suspicious activity: repeated rejected/reversed redemptions in the last 24 hours. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> suspicious() {
        return jdbc.queryForList(
                "SELECT u.full_name AS name, u.email, count(*) AS n FROM notifications nt JOIN users u ON u.id = nt.user_id "
                        + "WHERE nt.type = 'REJECTED' AND nt.created_at >= now() - interval '24 hours' GROUP BY u.id HAVING count(*) >= 5 ORDER BY n DESC LIMIT 20");
    }

    @Transactional(readOnly = true)
    public List<ApplicationSetting> settings() { return settings.findAll(); }

    @Transactional
    public void saveSetting(Long adminId, String key, String value) {
        if (key == null || !key.matches("[a-z0-9_.]{1,80}") || value == null || value.length() > 500) throw new BusinessException("error.input.invalid");
        ApplicationSetting s = settings.findById(key).orElseGet(ApplicationSetting::new);
        s.setKey(key);
        s.setValue(value);
        settings.save(s);
        audit.log(adminId, null, "SETTING_SAVED", "ApplicationSetting", null, "key=" + key);
    }
}
