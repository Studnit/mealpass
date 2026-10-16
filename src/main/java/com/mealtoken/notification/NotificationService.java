package com.mealtoken.notification;

import com.mealtoken.entity.Notification;
import com.mealtoken.repository.NotificationRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications. The stored message is "messageKey|arg1|arg2" so it is rendered in each
 * viewer's own language ({@link NotificationText}). Arguments starting with "~" are message keys.
 */
@Service
public class NotificationService {
    private final NotificationRepository repo;
    private final ObjectProvider<ExternalNotificationSender> external;

    public NotificationService(NotificationRepository repo, ObjectProvider<ExternalNotificationSender> external) {
        this.repo = repo; this.external = external;
    }

    /** Joins the caller's transaction (rolled back with it). */
    @Transactional
    public void notify(Long userId, String type, String key, Object... args) {
        save(userId, type, key, args);
    }

    /** Own transaction: survives a rollback of the caller (e.g. "redemption rejected"). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyIndependent(Long userId, String type, String key, Object... args) {
        save(userId, type, key, args);
    }

    /** Sends the notification only if an identical one does not already exist (daily job de-duplication). */
    @Transactional
    public void notifyOnce(Long userId, String type, String key, Object... args) {
        if (!repo.existsByUserIdAndMessage(userId, encode(key, args))) save(userId, type, key, args);
    }

    private static String encode(String key, Object[] args) {
        StringBuilder sb = new StringBuilder(key);
        for (Object a : args) sb.append('|').append(String.valueOf(a).replace('|', '/'));
        return sb.length() > 400 ? sb.substring(0, 400) : sb.toString();
    }

    private void save(Long userId, String type, String key, Object[] args) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        String msg = encode(key, args);
        n.setMessage(msg);
        repo.save(n);
        external.ifAvailable(x -> x.send(userId, type, msg)); // optional email / SMS / WhatsApp hook
    }

    @Transactional(readOnly = true)
    public Page<Notification> page(Long userId, int page) {
        return repo.findByUserIdOrderByIdDesc(userId, PageRequest.of(Math.max(page, 0), 20));
    }

    @Transactional(readOnly = true)
    public long unread(Long userId) { return repo.countByUserIdAndReadFalse(userId); }

    @Transactional
    public void markAllRead(Long userId) { repo.markAllRead(userId); }
}
