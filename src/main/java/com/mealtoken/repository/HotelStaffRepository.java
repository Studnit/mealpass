package com.mealtoken.repository;

import com.mealtoken.entity.HotelStaff;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface HotelStaffRepository extends JpaRepository<HotelStaff, Long> {
    Optional<HotelStaff> findByUserIdAndActiveTrue(Long userId);
    Optional<HotelStaff> findByIdAndHotelId(Long id, Long hotelId);
    List<HotelStaff> findByHotelId(Long hotelId);
}
