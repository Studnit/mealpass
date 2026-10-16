package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.dto.RegistrationForm;
import com.mealtoken.entity.*;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.notification.NotificationService;
import com.mealtoken.repository.*;
import com.mealtoken.security.HotelContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;

@Service
public class HotelService {
    private static final Set<String> STATUSES = Set.of("ACTIVE", "SUSPENDED", "DEACTIVATED");

    private final HotelRepository hotels;
    private final MealSlotRepository slots;
    private final SubscriptionPlanRepository plans;
    private final QrService qr;
    private final RegistrationService registration;
    private final AuditService audit;
    private final NotificationService notifications;
    private final Path uploadDir;

    public HotelService(HotelRepository hotels, MealSlotRepository slots, SubscriptionPlanRepository plans, QrService qr,
                        RegistrationService registration, AuditService audit, NotificationService notifications,
                        @Value("${app.upload-dir:uploads}") String uploadDir) {
        this.hotels = hotels; this.slots = slots; this.plans = plans; this.qr = qr; this.registration = registration;
        this.audit = audit; this.notifications = notifications; this.uploadDir = Path.of(uploadDir);
    }

    // ------------------------------------------------------------------ Super Admin

    /** Approve / suspend / deactivate. First activation provisions default slots, draft plans and the QR code. */
    @Transactional
    public void setStatus(Long adminId, Long hotelId, String status) {
        if (!STATUSES.contains(status)) throw new BusinessException("error.forbidden");
        Hotel h = hotels.findById(hotelId).orElseThrow(() -> new BusinessException("error.notfound"));
        h.setStatus(status);
        hotels.save(h);
        if ("ACTIVE".equals(status)) provisionDefaults(h);
        audit.log(adminId, hotelId, "HOTEL_" + status, "Hotel", hotelId, null);
        notifications.notify(h.getOwnerId(), "HOTEL", "notif.hotel." + status.toLowerCase(), h.getName());
    }

    /** Admin-created hotel with its owner account, immediately active. */
    @Transactional
    public Hotel createByAdmin(Long adminId, RegistrationForm form) {
        form.setAccountType("OWNER");
        User owner = registration.register(form, "en");
        Hotel h = hotels.findByOwnerId(owner.getId()).orElseThrow();
        setStatus(adminId, h.getId(), "ACTIVE");
        return h;
    }

    private void provisionDefaults(Hotel h) {
        if (slots.findByHotelIdOrderByStartTime(h.getId()).isEmpty()) {
            slots.save(slot(h.getId(), "LUNCH", "Lunch", LocalTime.of(12, 0), LocalTime.of(15, 0)));
            slots.save(slot(h.getId(), "DINNER", "Dinner", LocalTime.of(19, 0), LocalTime.of(22, 0)));
        }
        if (plans.findByHotelIdOrderByPrice(h.getId()).isEmpty()) {
            // Drafts: inactive with price 0 so an owner must consciously price and publish them.
            plans.save(plan(h.getId(), "Plan A - One meal per day", 1, 30));
            plans.save(plan(h.getId(), "Plan B - Two meals per day", 2, 60));
        }
        qr.ensureActive(h.getId());
    }

    private static MealSlot slot(Long hotelId, String code, String name, LocalTime s, LocalTime e) {
        MealSlot m = new MealSlot();
        m.setHotelId(hotelId); m.setCode(code); m.setName(name); m.setStartTime(s); m.setEndTime(e);
        return m;
    }

    private static SubscriptionPlan plan(Long hotelId, String name, int meals, int tokens) {
        SubscriptionPlan p = new SubscriptionPlan();
        p.setHotelId(hotelId); p.setPlanName(name); p.setMealsPerDay(meals); p.setTotalTokens(tokens);
        p.setPrice(BigDecimal.ZERO); p.setValidityDays(30); p.setActive(false);
        return p;
    }

    // ------------------------------------------------------------------ Owner

    @Transactional
    public void updateProfile(HotelContext ctx, String name, String address, String phone, String operatingHours,
                              String timezone, boolean enforceMealWindow, int lowTokenThreshold) {
        if (!ctx.owner()) throw new BusinessException("error.forbidden");
        if (name == null || name.isBlank() || name.length() > 150) throw new BusinessException("error.hotel.name.required");
        try { ZoneId.of(timezone); } catch (DateTimeException | NullPointerException e) { throw new BusinessException("error.timezone.invalid"); }
        if (lowTokenThreshold < 0 || lowTokenThreshold > 100) throw new BusinessException("error.plan.invalid");
        Hotel h = hotels.findById(ctx.hotelId()).orElseThrow();
        h.setName(name.trim());
        h.setAddress(trim(address, 300));
        h.setPhone(trim(phone, 20));
        h.setOperatingHours(trim(operatingHours, 100));
        h.setTimezone(timezone);
        h.setEnforceMealWindow(enforceMealWindow);
        h.setLowTokenThreshold(lowTokenThreshold);
        hotels.save(h);
        audit.log(ctx.userId(), h.getId(), "HOTEL_PROFILE_UPDATED", "Hotel", h.getId(), null);
    }

    @Transactional
    public void saveSlot(HotelContext ctx, Long slotId, String code, String name, LocalTime start, LocalTime end, boolean active) {
        if (!ctx.owner()) throw new BusinessException("error.forbidden");
        if (name == null || name.isBlank() || start == null || end == null || !end.isAfter(start))
            throw new BusinessException("error.slot.time");
        MealSlot m = slotId == null ? new MealSlot()
                : slots.findByIdAndHotelId(slotId, ctx.hotelId()).orElseThrow(() -> new BusinessException("error.notfound"));
        if (slotId == null) {
            if (code == null || !code.matches("[A-Za-z0-9_]{1,20}")) throw new BusinessException("error.slot.time");
            m.setHotelId(ctx.hotelId());
            m.setCode(code.toUpperCase());
        }
        m.setName(name.trim());
        m.setStartTime(start);
        m.setEndTime(end);
        m.setActive(active);
        slots.save(m);
        audit.log(ctx.userId(), ctx.hotelId(), "MEAL_SLOT_SAVED", "MealSlot", m.getId(), null);
    }

    @Transactional
    public void uploadLogo(HotelContext ctx, MultipartFile file) {
        if (!ctx.owner()) throw new BusinessException("error.forbidden");
        if (file == null || file.isEmpty() || file.getSize() > 1_000_000) throw new BusinessException("error.logo.invalid");
        try {
            byte[] bytes = file.getBytes();
            String ext;
            if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') ext = "png";
            else if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) ext = "jpg";
            else throw new BusinessException("error.logo.invalid"); // verified by content, not by client-supplied name/type
            Path dir = uploadDir.resolve("logos");
            Files.createDirectories(dir);
            Hotel h = hotels.findById(ctx.hotelId()).orElseThrow();
            String fileName = "hotel-" + h.getId() + "." + ext;
            Path tmp = Files.createTempFile(dir, "up", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, dir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            h.setLogoPath(fileName);
            hotels.save(h);
            audit.log(ctx.userId(), h.getId(), "HOTEL_LOGO_UPDATED", "Hotel", h.getId(), null);
        } catch (IOException e) {
            throw new BusinessException("error.logo.invalid");
        }
    }

    /** @return the logo bytes and media type, or null when the hotel has none. */
    public LogoFile logo(Long hotelId) {
        return hotels.findById(hotelId).map(Hotel::getLogoPath).filter(p -> p != null && p.matches("hotel-\\d+\\.(png|jpg)")).map(p -> {
            try {
                Path f = uploadDir.resolve("logos").resolve(p);
                return Files.exists(f) ? new LogoFile(Files.readAllBytes(f), p.endsWith("png") ? "image/png" : "image/jpeg") : null;
            } catch (IOException e) { return null; }
        }).orElse(null);
    }

    public record LogoFile(byte[] bytes, String contentType) {}

    private static String trim(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
