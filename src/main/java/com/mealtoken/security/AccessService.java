package com.mealtoken.security;

import com.mealtoken.entity.Hotel;
import com.mealtoken.entity.HotelStaff;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.HotelRepository;
import com.mealtoken.repository.HotelStaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Resolves which hotel a user may act on. This is the backbone of tenant isolation. */
@Service
public class AccessService {
    private final HotelRepository hotels;
    private final HotelStaffRepository staff;

    public AccessService(HotelRepository hotels, HotelStaffRepository staff) {
        this.hotels = hotels; this.staff = staff;
    }

    @Transactional(readOnly = true)
    public HotelContext context(AppUserDetails u) {
        boolean isOwner = u.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_OWNER"));
        if (isOwner) {
            Hotel h = hotels.findByOwnerId(u.getId()).orElseThrow(() -> new BusinessException("error.hotel.none"));
            return new HotelContext(h, u.getId(), true, true);
        }
        HotelStaff hs = staff.findByUserIdAndActiveTrue(u.getId()).orElseThrow(() -> new BusinessException("error.hotel.none"));
        Hotel h = hotels.findById(hs.getHotelId()).orElseThrow(() -> new BusinessException("error.hotel.none"));
        return new HotelContext(h, u.getId(), false, hs.isCanConfirmPayments());
    }

    public HotelContext current() { return context(CurrentUser.require()); }

    /** Operational features need the hotel to be approved and live. */
    public HotelContext currentActive() {
        HotelContext c = current();
        if (!"ACTIVE".equals(c.hotel().getStatus())) throw new BusinessException("error.hotel.inactive");
        return c;
    }

    public HotelContext currentOwnerActive() {
        HotelContext c = currentActive();
        if (!c.owner()) throw new BusinessException("error.forbidden");
        return c;
    }
}
