package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

/** Append-only ledger row (UPDATE/DELETE are blocked by a database trigger). */
@Entity @Table(name = "token_transactions") @Getter @Setter
public class TokenTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "subscription_id", nullable = false, updatable = false) private Long subscriptionId;
    @Column(name = "transaction_type", nullable = false, updatable = false, length = 20) private String transactionType;
    @Column(nullable = false, updatable = false) private int amount;
    @Column(name = "balance_before", nullable = false, updatable = false) private int balanceBefore;
    @Column(name = "balance_after", nullable = false, updatable = false) private int balanceAfter;
    @Column(name = "reference_number", nullable = false, updatable = false, length = 40) private String referenceNumber;
    @Column(length = 300, updatable = false) private String reason;
    @Column(name = "created_by", updatable = false) private Long createdBy;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
}
