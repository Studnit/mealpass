package com.mealtoken.security;

import com.mealtoken.entity.Hotel;

/** The hotel the signed-in owner/staff member operates on. It is derived server-side, never from request input. */
public record HotelContext(Hotel hotel, Long userId, boolean owner, boolean canConfirmPayments) {
    public Long hotelId() { return hotel.getId(); }
}
