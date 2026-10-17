package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.HotelStaff;
import com.mealtoken.entity.User;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.*;
import com.mealtoken.security.HotelContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class StaffService {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UserRepository users;
    private final RoleRepository roles;
    private final HotelStaffRepository staff;
    private final PasswordEncoder encoder;
    private final AuditService audit;

    public StaffService(UserRepository users, RoleRepository roles, HotelStaffRepository staff,
                        PasswordEncoder encoder, AuditService audit) {
        this.users = users; this.roles = roles; this.staff = staff; this.encoder = encoder; this.audit = audit;
    }

    @Transactional
    public void create(HotelContext ctx, String fullName, String email, String phone, String password, boolean canConfirmPayments) {
        requireOwner(ctx);
        if (fullName == null || fullName.isBlank() || email == null || !EMAIL.matcher(email.trim()).matches())
            throw new BusinessException("error.staff.invalid");
        if (password == null || password.length() < 8 || password.length() > 72) throw new BusinessException("error.password.short");
        String mail = email.trim().toLowerCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(mail)) throw new BusinessException("error.email.exists");

        User u = new User();
        u.setFullName(fullName.trim());
        u.setEmail(mail);
        u.setPhone(com.mealtoken.util.Validators.mobileOrNull(phone));
        u.setPasswordHash(encoder.encode(password));
        u.getRoles().add(roles.findByName("STAFF").orElseThrow());
        u = users.save(u);

        HotelStaff hs = new HotelStaff();
        hs.setHotelId(ctx.hotelId());
        hs.setUserId(u.getId());
        hs.setCanConfirmPayments(canConfirmPayments);
        staff.save(hs);
        audit.log(ctx.userId(), ctx.hotelId(), "STAFF_CREATED", "User", u.getId(), "confirmPayments=" + canConfirmPayments);
    }

    @Transactional
    public void update(HotelContext ctx, Long staffId, boolean active, boolean canConfirmPayments) {
        requireOwner(ctx);
        HotelStaff hs = staff.findByIdAndHotelId(staffId, ctx.hotelId()).orElseThrow(() -> new BusinessException("error.notfound"));
        hs.setActive(active);
        hs.setCanConfirmPayments(canConfirmPayments);
        User u = users.findById(hs.getUserId()).orElseThrow();
        u.setStatus(active ? "ACTIVE" : "SUSPENDED"); // a deactivated member cannot sign in at all
        audit.log(ctx.userId(), ctx.hotelId(), "STAFF_UPDATED", "User", u.getId(), "active=" + active + " confirmPayments=" + canConfirmPayments);
    }

    @Transactional
    public void resetPassword(HotelContext ctx, Long staffId, String newPassword) {
        requireOwner(ctx);
        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 72) throw new BusinessException("error.password.short");
        HotelStaff hs = staff.findByIdAndHotelId(staffId, ctx.hotelId()).orElseThrow(() -> new BusinessException("error.notfound"));
        User u = users.findById(hs.getUserId()).orElseThrow();
        u.setPasswordHash(encoder.encode(newPassword));
        audit.log(ctx.userId(), ctx.hotelId(), "STAFF_PASSWORD_RESET", "User", u.getId(), null);
    }

    private static void requireOwner(HotelContext ctx) {
        if (!ctx.owner()) throw new BusinessException("error.forbidden");
    }
}
