package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;
import java.time.LocalDate;

@Entity @Table(name = "menu_items") @Getter @Setter
public class MenuItem {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hotel_id", nullable = false) private Long hotelId;
    @Column(name = "menu_date", nullable = false) private LocalDate menuDate;
    @Column(name = "meal_slot_id") private Long mealSlotId;
    @Column(nullable = false, length = 120) private String name;
    @Column(length = 300) private String description;
    @Column(nullable = false) private boolean veg = true;
    @Column(nullable = false) private boolean available = true;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false) private Instant createdAt;
}
