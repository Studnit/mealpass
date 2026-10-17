package com.mealtoken.repository;

import com.mealtoken.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(@Param("id") Long id);
    Optional<Payment> findByIdAndHotelId(Long id, Long hotelId);
    Optional<Payment> findByIdAndCustomerId(Long id, Long customerId);
    List<Payment> findBySubscriptionIdOrderByIdAsc(Long subscriptionId);
    Optional<Payment> findFirstBySubscriptionIdAndStatusOrderByIdDesc(Long subscriptionId, String status);
    @Query("select coalesce(sum(p.amount), 0) from Payment p where p.subscriptionId = :id and p.status = 'SUCCESS'")
    java.math.BigDecimal sumPaid(@Param("id") Long subscriptionId);
    List<Payment> findByHotelIdAndStatusOrderByCreatedAt(Long hotelId, String status);
}
