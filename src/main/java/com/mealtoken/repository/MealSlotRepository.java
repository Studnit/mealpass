package com.mealtoken.repository;

import com.mealtoken.entity.MealSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MealSlotRepository extends JpaRepository<MealSlot, Long> {
    List<MealSlot> findByHotelIdAndActiveTrueOrderByStartTime(Long hotelId);
    List<MealSlot> findByHotelIdOrderByStartTime(Long hotelId);
    Optional<MealSlot> findByIdAndHotelId(Long id, Long hotelId);
}
