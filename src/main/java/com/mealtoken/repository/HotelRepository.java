package com.mealtoken.repository;

import com.mealtoken.entity.Hotel;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface HotelRepository extends JpaRepository<Hotel, Long> {
    Optional<Hotel> findByOwnerId(Long ownerId);
    List<Hotel> findByStatus(String status);
    boolean existsBySlug(String slug);
    List<Hotel> findByStatusOrderByName(String status);
}
