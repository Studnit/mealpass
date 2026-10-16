package com.mealtoken.repository;

import com.mealtoken.entity.HotelQrCode;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface HotelQrCodeRepository extends JpaRepository<HotelQrCode, Long> {
    Optional<HotelQrCode> findByHotelIdAndStatus(Long hotelId, String status);
    Optional<HotelQrCode> findByPublicIdentifierAndStatus(String id, String status);
    Optional<HotelQrCode> findByPublicIdentifier(String id);
}
