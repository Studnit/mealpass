package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "meal_attendance") @Getter @Setter
public class MealAttendance {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "customer_id", nullable = false) private Long customerId;
    @Column(name = "hotel_id", nullable = false) private Long hotelId;
    @Column(name = "subscription_id", nullable = false) private Long subscriptionId;
    @Column(name = "meal_slot_id", nullable = false) private Long mealSlotId;
    @Column(name = "attendance_date", nullable = false) private LocalDate attendanceDate;
    @Column(name = "redeemed_at", nullable = false) private Instant redeemedAt = Instant.now();
    @Column(nullable = false, length = 20) private String status = "SERVED";
    @Column(name = "reference_number", nullable = false, unique = true, length = 40) private String referenceNumber;
    @Column(name = "idempotency_key", unique = true, length = 80) private String idempotencyKey;
    @Column(name = "created_by") private Long createdBy;
}
