package com.mealtoken.repository;

import com.mealtoken.entity.MealAttendance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MealAttendanceRepository extends JpaRepository<MealAttendance, Long> {
    Optional<MealAttendance> findByIdempotencyKey(String key);
    Optional<MealAttendance> findByIdAndHotelId(Long id, Long hotelId);
    Optional<MealAttendance> findByReferenceNumberAndCustomerId(String ref, Long customerId);
    long countBySubscriptionIdAndAttendanceDateAndStatus(Long subId, LocalDate date, String status);
    boolean existsBySubscriptionIdAndAttendanceDateAndMealSlotIdAndStatus(Long subId, LocalDate date, Long slotId, String status);
    List<MealAttendance> findByCustomerIdAndHotelIdAndAttendanceDateAndStatus(Long c, Long h, LocalDate d, String status);
}
