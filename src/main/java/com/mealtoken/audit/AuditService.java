package com.mealtoken.audit;

import com.mealtoken.entity.AuditLog;
import com.mealtoken.repository.AuditLogRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Writes append-only audit entries. Joins the caller's transaction so audit and action commit together. */
@Service
public class AuditService {
    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) { this.repo = repo; }

    @Transactional(propagation = Propagation.REQUIRED)
    public void log(Long actorUserId, Long hotelId, String action, String entityType, Long entityId, String details) {
        AuditLog l = new AuditLog();
        l.setActorUserId(actorUserId);
        l.setHotelId(hotelId);
        l.setAction(action);
        l.setEntityType(entityType);
        l.setEntityId(entityId);
        l.setDetails(details);
        repo.save(l);
    }
}
