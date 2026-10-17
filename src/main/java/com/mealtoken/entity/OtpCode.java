package com.mealtoken.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "otp_codes")
public class OtpCode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(name = "code_hash", nullable = false, length = 64) private String codeHash;
    @Column(nullable = false) private int attempts;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "consumed_at") private Instant consumedAt;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String v) { this.codeHash = v; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int v) { this.attempts = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { this.expiresAt = v; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant v) { this.consumedAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
