package com.mealtoken.repository;

import com.mealtoken.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {
    Page<Notification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);
    long countByUserIdAndReadFalse(Long userId);
    java.util.List<Notification> findTop20ByUserIdAndIdGreaterThanOrderByIdAsc(Long userId, Long id);
    java.util.Optional<Notification> findFirstByUserIdOrderByIdDesc(Long userId);
    boolean existsByUserIdAndMessage(Long userId, String message);
    @Modifying
    @Query("update Notification n set n.read = true where n.userId = :u and n.read = false")
    int markAllRead(@Param("u") Long userId);
}
