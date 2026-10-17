package com.mealtoken.repository;

import com.mealtoken.entity.OtpCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OtpCodeRepository extends JpaRepository<OtpCode, Long> {
    Optional<OtpCode> findFirstByUserIdAndConsumedAtIsNullOrderByIdDesc(Long userId);
    Optional<OtpCode> findFirstByUserIdOrderByIdDesc(Long userId);
}
