package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "subscription_requests") @Getter @Setter
public class SubscriptionRequest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false) private Long customerId;
    @Column(name = "hotel_id", nullable = false) private Long hotelId;
    @Column(name = "plan_id", nullable = false) private Long planId;
    @Column(nullable = false, length = 20) private String status = "PENDING";
    @Column(length = 300) private String note;
    @Column(name = "decided_by") private Long decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
