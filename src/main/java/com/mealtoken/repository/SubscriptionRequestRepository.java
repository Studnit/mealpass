package com.mealtoken.repository;

import com.mealtoken.entity.SubscriptionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRequestRepository extends JpaRepository<SubscriptionRequest, Long> {
    List<SubscriptionRequest> findByHotelIdAndStatusOrderByCreatedAt(Long hotelId, String status);
    List<SubscriptionRequest> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    Optional<SubscriptionRequest> findByIdAndHotelId(Long id, Long hotelId);
    boolean existsByCustomerIdAndHotelIdAndStatus(Long customerId, Long hotelId, String status);
}
