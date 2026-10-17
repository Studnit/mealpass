package com.mealtoken.controller;

import com.mealtoken.dto.RegistrationForm;
import com.mealtoken.security.CurrentUser;
import com.mealtoken.service.AdminService;
import com.mealtoken.service.HotelService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin")
public class AdminController {
    private final AdminService admin;
    private final HotelService hotelService;

    public AdminController(AdminService admin, HotelService hotelService) { this.admin = admin; this.hotelService = hotelService; }

    @GetMapping("/hotels")
    public String hotels(@RequestParam(required = false) String status, Model m) {
        m.addAttribute("hotels", admin.hotels(status));
        m.addAttribute("status", status == null ? "" : status);
        m.addAttribute("statuses", java.util.List.of("PENDING", "ACTIVE", "SUSPENDED", "DEACTIVATED"));
        m.addAttribute("section", "hotels");
        return "admin/hotels";
    }

    @PostMapping("/hotels/{id}/status")
    public String hotelStatus(@PathVariable Long id, @RequestParam String status, RedirectAttributes ra) {
        hotelService.setStatus(CurrentUser.require().getId(), id, status);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/admin/hotels";
    }

    @PostMapping("/hotels")
    public String createHotel(@RequestParam String hotelName, @RequestParam String fullName, @RequestParam String email,
                              @RequestParam(required = false) String phone, @RequestParam String password,
                              @RequestParam(required = false) String city, @RequestParam(required = false) String area, RedirectAttributes ra) {
        RegistrationForm f = new RegistrationForm();
        f.setHotelName(hotelName); f.setFullName(fullName); f.setEmail(email); f.setPhone(phone); f.setPassword(password); f.setCity(city); f.setArea(area);
        hotelService.createByAdmin(CurrentUser.require().getId(), f);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/admin/hotels";
    }

    @GetMapping("/users")
    public String users(@RequestParam(required = false) String q, Model m) {
        m.addAttribute("users", admin.users(q));
        m.addAttribute("q", q == null ? "" : q);
        m.addAttribute("section", "users");
        return "admin/users";
    }

    @PostMapping("/users/{id}/status")
    public String userStatus(@PathVariable Long id, @RequestParam String status, RedirectAttributes ra) {
        admin.setUserStatus(CurrentUser.require().getId(), id, status);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/admin/users";
    }

    @GetMapping("/audit")
    public String audit(@RequestParam(defaultValue = "0") int page, Model m) {
        m.addAttribute("rows", admin.platformAudit(page));
        m.addAttribute("suspicious", admin.suspicious());
        m.addAttribute("page", Math.max(page, 0));
        m.addAttribute("section", "audit");
        return "admin/audit";
    }

    @GetMapping("/settings")
    public String settings(Model m) {
        m.addAttribute("settings", admin.settings());
        m.addAttribute("section", "settings");
        return "admin/settings";
    }

    @PostMapping("/settings")
    public String saveSetting(@RequestParam String key, @RequestParam String value, RedirectAttributes ra) {
        admin.saveSetting(CurrentUser.require().getId(), key, value);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/admin/settings";
    }
}
