package com.mealtoken.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

@Entity @Table(name = "subscription_plans") @Getter @Setter
public class SubscriptionPlan {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "hotel_id", nullable = false) private Long hotelId;
    @Column(name = "plan_name", nullable = false, length = 100) private String planName;
    @Column(name = "meals_per_day", nullable = false) private int mealsPerDay;
    @Column(name = "total_tokens", nullable = false) private int totalTokens;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal price;
    @Column(name = "validity_days", nullable = false) private int validityDays;
    @Column(name = "tokens_expire_with_subscription", nullable = false) private boolean tokensExpireWithSubscription = true;
    @Column(nullable = false) private boolean active = true;
}
