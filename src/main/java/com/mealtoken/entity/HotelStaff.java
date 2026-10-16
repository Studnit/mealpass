package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name = "hotel_staff") @Getter @Setter
public class HotelStaff {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hotel_id", nullable = false) private Long hotelId;
    @Column(name = "user_id", nullable = false) private Long userId;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "can_confirm_payments", nullable = false) private boolean canConfirmPayments = false;
}
