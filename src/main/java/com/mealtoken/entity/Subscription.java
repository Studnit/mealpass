package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "subscriptions") @Getter @Setter
public class Subscription {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false) private Long customerId;
    @Column(name = "hotel_id", nullable = false) private Long hotelId;
    @Column(name = "plan_id", nullable = false) private Long planId;
    @Column(name = "start_date") private LocalDate startDate;
    @Column(name = "expiry_date") private LocalDate expiryDate;
    @Column(name = "total_tokens", nullable = false) private int totalTokens;
    @Column(name = "used_tokens", nullable = false) private int usedTokens;
    @Column(name = "remaining_tokens", nullable = false) private int remainingTokens;
    @Column(nullable = false, length = 20) private String status = "PENDING";
    @Column(name = "payment_status", nullable = false, length = 20) private String paymentStatus = "PENDING";
    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2) private java.math.BigDecimal totalAmount = java.math.BigDecimal.ZERO;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Version private Long version;
}
