package com.mealtoken.controller;

import com.mealtoken.entity.HotelQrCode;
import com.mealtoken.repository.MealSlotRepository;
import com.mealtoken.repository.SubscriptionPlanRepository;
import com.mealtoken.security.AccessService;
import com.mealtoken.security.HotelContext;
import com.mealtoken.service.*;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Everything the Hotel Owner manages. The hotel is always derived from the session, never from the request. */
@Controller
@RequestMapping("/owner")
public class OwnerController {
    private final AccessService access;
    private final HotelService hotelService;
    private final PlanService planService;
    private final SubscriptionService subscriptions;
    private final OwnerQueryService queries;
    private final QrService qr;
    private final StaffService staffService;
    private final SubscriptionPlanRepository plans;
    private final MealSlotRepository slots;
    private final RedemptionService redemption;

    public OwnerController(AccessService access, HotelService hotelService, PlanService planService,
                           SubscriptionService subscriptions, OwnerQueryService queries, QrService qr,
                           StaffService staffService, SubscriptionPlanRepository plans, MealSlotRepository slots,
                           RedemptionService redemption) {
        this.access = access; this.hotelService = hotelService; this.planService = planService;
        this.subscriptions = subscriptions; this.queries = queries; this.qr = qr; this.staffService = staffService;
        this.plans = plans; this.slots = slots; this.redemption = redemption;
    }

    // ------------------------------------------------------------------ hotel profile & settings

    @GetMapping("/hotel")
    public String hotel(Model m) {
        HotelContext ctx = access.current();
        m.addAttribute("hotel", ctx.hotel());
        m.addAttribute("slots", slots.findByHotelIdOrderByStartTime(ctx.hotelId()));
        List<String> zones = new ArrayList<>(ZoneId.getAvailableZoneIds());
        Collections.sort(zones);
        m.addAttribute("zones", zones);
        m.addAttribute("section", "hotel");
        return "owner/hotel";
    }

    @PostMapping("/hotel")
    public String saveHotel(@RequestParam String name, @RequestParam(required = false) String address,
                            @RequestParam(required = false) String city, @RequestParam(required = false) String area,
                            @RequestParam(required = false) String phone, @RequestParam(required = false) String operatingHours,
                            @RequestParam String timezone, @RequestParam(defaultValue = "false") boolean enforceMealWindow,
                            @RequestParam(defaultValue = "5") int lowTokenThreshold,
                            @RequestParam(defaultValue = "5") int tokenHoldCount, RedirectAttributes ra) {
        hotelService.updateProfile(access.current(), name, address, city, area, phone, operatingHours, timezone, enforceMealWindow, lowTokenThreshold, tokenHoldCount);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/hotel";
    }

    @PostMapping("/hotel/logo")
    public String logo(@RequestParam("file") MultipartFile file, RedirectAttributes ra) {
        hotelService.uploadLogo(access.current(), file);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/hotel";
    }

    @PostMapping("/hotel/slots")
    public String saveSlot(@RequestParam(required = false) Long slotId, @RequestParam(required = false) String code,
                           @RequestParam String name,
                           @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime start,
                           @RequestParam @DateTimeFormat(pattern = "HH:mm") LocalTime end,
                           @RequestParam(defaultValue = "false") boolean active, RedirectAttributes ra) {
        hotelService.saveSlot(access.current(), slotId, code, name, start, end, active);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/hotel";
    }

    // ------------------------------------------------------------------ plans

    @GetMapping("/plans")
    public String plans(Model m) {
        HotelContext ctx = access.current();
        m.addAttribute("plans", plans.findByHotelIdOrderByPrice(ctx.hotelId()));
        m.addAttribute("section", "plans");
        return "owner/plans";
    }

    @PostMapping("/plans")
    public String savePlan(@RequestParam(required = false) Long planId, @RequestParam String planName,
                           @RequestParam int mealsPerDay, @RequestParam int totalTokens, @RequestParam BigDecimal price,
                           @RequestParam int validityDays, @RequestParam(defaultValue = "false") boolean tokensExpire,
                           @RequestParam(defaultValue = "false") boolean active, RedirectAttributes ra) {
        planService.save(access.current(), planId, planName, mealsPerDay, totalTokens, price, validityDays, tokensExpire, active);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/plans";
    }

    // ------------------------------------------------------------------ requests, subscriptions

    @GetMapping("/requests")
    public String requests(Model m) {
        HotelContext ctx = access.current();
        m.addAttribute("requests", queries.pendingRequests(ctx.hotelId()));
        m.addAttribute("payments", queries.pendingPayments(ctx.hotelId()));
        m.addAttribute("section", "requests");
        return "owner/requests";
    }

    @PostMapping("/requests/{id}/approve")
    public String approve(@PathVariable Long id, RedirectAttributes ra) {
        subscriptions.approve(access.currentOwnerActive(), id);
        ra.addFlashAttribute("ok", "ok.approved");
        return "redirect:/owner/requests";
    }

    @PostMapping("/requests/{id}/reject")
    public String reject(@PathVariable Long id, @RequestParam(required = false) String note, RedirectAttributes ra) {
        subscriptions.reject(access.currentOwnerActive(), id, note);
        ra.addFlashAttribute("ok", "ok.rejected");
        return "redirect:/owner/requests";
    }

    @PostMapping("/subscriptions/{id}/suspend")
    public String suspend(@PathVariable Long id, RedirectAttributes ra) {
        subscriptions.suspend(access.currentOwnerActive(), id);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/table/subscriptions";
    }

    @PostMapping("/subscriptions/{id}/resume")
    public String resume(@PathVariable Long id, RedirectAttributes ra) {
        subscriptions.resume(access.currentOwnerActive(), id);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/table/subscriptions";
    }

    @PostMapping("/subscriptions/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        subscriptions.cancel(access.currentOwnerActive(), id);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/table/subscriptions";
    }

    @PostMapping("/subscriptions/{id}/extend")
    public String extend(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiry,
                         RedirectAttributes ra) {
        subscriptions.extend(access.currentOwnerActive(), id, expiry);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/table/subscriptions";
    }

    @PostMapping("/attendance/{id}/reverse")
    public String reverse(@PathVariable Long id, @RequestParam String reason, RedirectAttributes ra) {
        HotelContext ctx = access.currentOwnerActive();
        redemption.reverse(ctx.hotelId(), id, ctx.userId(), reason);
        ra.addFlashAttribute("ok", "ok.reversed");
        return "redirect:/owner/table/attendance";
    }

    // ------------------------------------------------------------------ QR management

    @GetMapping("/qr")
    public String qrPage(Model m) {
        HotelContext ctx = access.current();
        m.addAttribute("hotel", ctx.hotel());
        qr.active(ctx.hotelId()).ifPresent(q -> { m.addAttribute("qr", q); m.addAttribute("qrUrl", qr.urlFor(q)); });
        m.addAttribute("section", "qr");
        return "owner/qr";
    }

    @GetMapping("/qr/print")
    public String qrPrint(Model m) {
        HotelContext ctx = access.current();
        m.addAttribute("hotel", ctx.hotel());
        qr.active(ctx.hotelId()).ifPresent(q -> { m.addAttribute("qr", q); m.addAttribute("qrUrl", qr.urlFor(q)); });
        return "owner/qr-print";
    }

    @GetMapping(value = "/qr/image.png", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> qrImage(@RequestParam(defaultValue = "false") boolean download) {
        HotelContext ctx = access.currentActive();
        HotelQrCode q = qr.ensureActive(ctx.hotelId());
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore());
        if (download) b.header("Content-Disposition", "attachment; filename=\"hotel-qr-" + ctx.hotel().getSlug() + ".png\"");
        return b.body(qr.png(qr.urlFor(q), 640));
    }

    @PostMapping("/qr/rotate")
    public String rotate(RedirectAttributes ra) {
        HotelContext ctx = access.currentOwnerActive();
        qr.rotate(ctx.hotelId(), ctx.userId());
        ra.addFlashAttribute("ok", "ok.qr.rotated");
        return "redirect:/owner/qr";
    }

    @PostMapping("/qr/deactivate")
    public String deactivate(RedirectAttributes ra) {
        HotelContext ctx = access.currentOwnerActive();
        qr.deactivate(ctx.hotelId(), ctx.userId());
        ra.addFlashAttribute("ok", "ok.qr.deactivated");
        return "redirect:/owner/qr";
    }

    // ------------------------------------------------------------------ staff

    @GetMapping("/staff")
    public String staff(Model m) {
        HotelContext ctx = access.current();
        m.addAttribute("staff", queries.staff(ctx.hotelId()));
        m.addAttribute("section", "staff");
        return "owner/staff";
    }

    @PostMapping("/staff")
    public String createStaff(@RequestParam String fullName, @RequestParam String email, @RequestParam(required = false) String phone,
                              @RequestParam String password, @RequestParam(defaultValue = "false") boolean canConfirmPayments,
                              RedirectAttributes ra) {
        staffService.create(access.currentOwnerActive(), fullName, email, phone, password, canConfirmPayments);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/staff";
    }

    @PostMapping("/staff/{id}")
    public String updateStaff(@PathVariable Long id, @RequestParam(defaultValue = "false") boolean active,
                              @RequestParam(defaultValue = "false") boolean canConfirmPayments, RedirectAttributes ra) {
        staffService.update(access.currentOwnerActive(), id, active, canConfirmPayments);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/staff";
    }

    @PostMapping("/staff/{id}/password")
    public String staffPassword(@PathVariable Long id, @RequestParam String password, RedirectAttributes ra) {
        staffService.resetPassword(access.currentOwnerActive(), id, password);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/staff";
    }

    // ------------------------------------------------------------------ reports index

    @GetMapping("/reports")
    public String reports(Model m) {
        access.current();
        m.addAttribute("reports", ReportService.ownerReports());
        m.addAttribute("section", "reports");
        return "owner/reports";
    }
}
