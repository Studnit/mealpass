package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "audit_logs") @Getter @Setter
public class AuditLog {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "actor_user_id", updatable = false) private Long actorUserId;
    @Column(name = "hotel_id", updatable = false) private Long hotelId;
    @Column(nullable = false, updatable = false, length = 60) private String action;
    @Column(name = "entity_type", updatable = false, length = 60) private String entityType;
    @Column(name = "entity_id", updatable = false) private Long entityId;
    @Column(columnDefinition = "text", updatable = false) private String details;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
