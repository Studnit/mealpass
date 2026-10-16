package com.mealtoken.repository;

import com.mealtoken.entity.Subscription;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {

    /** Row-locks the customer's single ACTIVE subscription at a hotel (serialises concurrent redemptions). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.customerId = :c and s.hotelId = :h and s.status = 'ACTIVE'")
    Optional<Subscription> lockActive(@Param("c") Long customerId, @Param("h") Long hotelId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Subscription s where s.id = :id")
    Optional<Subscription> lockById(@Param("id") Long id);

    List<Subscription> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    Optional<Subscription> findByIdAndCustomerId(Long id, Long customerId);
    Optional<Subscription> findByIdAndHotelId(Long id, Long hotelId);
    boolean existsByCustomerIdAndHotelIdAndStatusIn(Long customerId, Long hotelId, List<String> statuses);
    boolean existsByCustomerIdAndHotelIdAndStatus(Long customerId, Long hotelId, String status);
    List<Subscription> findByHotelIdAndStatusAndExpiryDateLessThan(Long hotelId, String status, LocalDate date);
    List<Subscription> findByHotelIdAndStatusAndExpiryDate(Long hotelId, String status, LocalDate date);
    List<Subscription> findByCustomerIdAndHotelIdOrderByCreatedAtDesc(Long customerId, Long hotelId);
}
