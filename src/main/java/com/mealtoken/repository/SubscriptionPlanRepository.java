package com.mealtoken.repository;

import com.mealtoken.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {
    List<SubscriptionPlan> findByHotelIdOrderByPrice(Long hotelId);
    List<SubscriptionPlan> findByHotelIdAndActiveTrueOrderByPrice(Long hotelId);
    Optional<SubscriptionPlan> findByIdAndHotelId(Long id, Long hotelId);
}
