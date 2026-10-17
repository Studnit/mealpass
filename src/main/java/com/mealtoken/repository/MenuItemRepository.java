package com.mealtoken.repository;

import com.mealtoken.entity.MenuItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MenuItemRepository extends JpaRepository<MenuItem, Long> {
    List<MenuItem> findByHotelIdAndMenuDateOrderBySortOrderAscIdAsc(Long hotelId, LocalDate date);
    Optional<MenuItem> findByIdAndHotelId(Long id, Long hotelId);
}
