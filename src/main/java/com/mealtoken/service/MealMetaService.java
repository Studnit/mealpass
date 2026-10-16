package com.mealtoken.service;

import com.mealtoken.entity.Hotel;
import com.mealtoken.entity.MealAttendance;
import com.mealtoken.entity.MealSlot;
import com.mealtoken.repository.MealAttendanceRepository;
import com.mealtoken.repository.MealSlotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Slot helpers for the redemption screens (read-only; the authoritative checks live in RedemptionService). */
@Service
public class MealMetaService {
    private final MealSlotRepository slots;
    private final MealAttendanceRepository attendance;
    private final Clock clock;

    public MealMetaService(MealSlotRepository slots, MealAttendanceRepository attendance, Clock clock) {
        this.slots = slots; this.attendance = attendance; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<MealSlot> activeSlots(Long hotelId) { return slots.findByHotelIdAndActiveTrueOrderByStartTime(hotelId); }

    @Transactional(readOnly = true)
    public Set<Long> servedSlotIdsToday(Long customerId, Hotel hotel) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of(hotel.getTimezone())));
        return attendance.findByCustomerIdAndHotelIdAndAttendanceDateAndStatus(customerId, hotel.getId(), today, "SERVED")
                .stream().map(MealAttendance::getMealSlotId).collect(Collectors.toSet());
    }
}
