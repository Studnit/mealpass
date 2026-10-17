package com.mealtoken.controller;

import com.mealtoken.security.AccessService;
import com.mealtoken.security.HotelContext;
import com.mealtoken.service.MenuService;
import com.mealtoken.repository.MealSlotRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;

/** Owner screen for the daily menu. The hotel always comes from the signed-in owner, never from the request. */
@Controller
@RequestMapping("/owner/menu")
public class MenuController {
    private final AccessService access;
    private final MenuService menu;
    private final MealSlotRepository slots;

    public MenuController(AccessService access, MenuService menu, MealSlotRepository slots) {
        this.access = access; this.menu = menu; this.slots = slots;
    }

    @GetMapping
    public String page(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, Model m) {
        HotelContext ctx = access.current();
        LocalDate today = menu.today(ctx.hotel());
        LocalDate d = date == null || date.isBefore(today) ? today : date;
        m.addAttribute("hotel", ctx.hotel());
        m.addAttribute("date", d);
        m.addAttribute("today", today);
        m.addAttribute("maxDate", today.plusDays(7));
        m.addAttribute("groups", menu.groups(ctx.hotelId(), d));
        m.addAttribute("slots", slots.findByHotelIdAndActiveTrueOrderByStartTime(ctx.hotelId()));
        m.addAttribute("section", "daily_menu");
        return "owner/menu";
    }

    @PostMapping("/add")
    public String add(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                      @RequestParam(required = false) Long slotId, @RequestParam String name,
                      @RequestParam(required = false) String description, @RequestParam(defaultValue = "true") boolean veg,
                      RedirectAttributes ra) {
        menu.add(access.currentOwnerActive(), date, slotId, name, description, veg);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/menu?date=" + date;
    }

    @PostMapping("/{id}/toggle")
    public String toggle(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, RedirectAttributes ra) {
        menu.toggleAvailable(access.currentOwnerActive(), id);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/menu?date=" + date;
    }

    @PostMapping("/{id}/delete")
    public String delete(@PathVariable Long id, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, RedirectAttributes ra) {
        menu.delete(access.currentOwnerActive(), id);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/menu?date=" + date;
    }

    @PostMapping("/copy")
    public String copy(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, RedirectAttributes ra) {
        menu.copy(access.currentOwnerActive(), date.minusDays(1), date);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/owner/menu?date=" + date;
    }

    @PostMapping("/notify")
    public String notifyCustomers(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date, RedirectAttributes ra) {
        menu.notifyCustomers(access.currentOwnerActive(), date);
        ra.addFlashAttribute("ok", "ok.menu.notified");
        return "redirect:/owner/menu?date=" + date;
    }
}
