package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.Instant;

@Entity @Table(name = "payments") @Getter @Setter
public class Payment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false) private Long customerId;
    @Column(name = "hotel_id", nullable = false) private Long hotelId;
    @Column(name = "subscription_id") private Long subscriptionId;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal amount;
    @Column(name = "payment_method", nullable = false, length = 20) private String paymentMethod;
    @Column(name = "payment_reference", length = 100) private String paymentReference;
    @Column(nullable = false, length = 20) private String status = "PENDING";
    @Column(name = "verified_by") private Long verifiedBy;
    @Column(name = "paid_at") private Instant paidAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "receipt_number", length = 30) private String receiptNumber;
    @Column(name = "gateway_provider", length = 30) private String gatewayProvider;
}
