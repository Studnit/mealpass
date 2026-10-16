package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "hotel_qr_codes") @Getter @Setter
public class HotelQrCode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hotel_id", nullable = false) private Long hotelId;
    @Column(name = "public_identifier", nullable = false, unique = true, length = 64) private String publicIdentifier;
    @Column(nullable = false, length = 10) private String status = "ACTIVE";
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt = Instant.now();
    @Column(name = "revoked_at") private Instant revokedAt;
}
