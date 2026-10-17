package com.mealtoken.controller;

import com.mealtoken.repository.HotelRepository;
import com.mealtoken.security.AccessService;
import com.mealtoken.security.AppUserDetails;
import com.mealtoken.security.CurrentUser;
import com.mealtoken.security.HotelContext;
import com.mealtoken.service.CustomerService;
import com.mealtoken.service.DashboardService;
import com.mealtoken.service.OwnerQueryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.Map;

@Controller
public class DashboardController {
    private final DashboardService dashboard;
    private final AccessService access;
    private final CustomerService customers;
    private final com.mealtoken.service.MenuService menus;
    private final OwnerQueryService queries;

    public DashboardController(DashboardService dashboard, AccessService access, CustomerService customers, OwnerQueryService queries,
                               com.mealtoken.service.MenuService menus) {
        this.dashboard = dashboard; this.access = access; this.customers = customers; this.queries = queries; this.menus = menus;
    }

    @GetMapping("/admin/dashboard")
    public String admin(Model m) {
        m.addAllAttributes(dashboard.admin());
        m.addAttribute("section", "dashboard");
        return "admin/dashboard";
    }

    @GetMapping("/owner/dashboard")
    public String owner(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to, Model m) {
        HotelContext ctx = access.current();
        m.addAttribute("hotel", ctx.hotel());
        m.addAllAttributes(dashboard.owner(ctx, from, to));
        m.addAttribute("section", "dashboard");
        return "owner/dashboard";
    }

    /** Staff see operational counts only: no revenue or subscription analytics. */
    @GetMapping("/staff/dashboard")
    public String staff(Model m) {
        HotelContext ctx = access.current();
        Map<String, Object> d = dashboard.owner(ctx, null, null);
        m.addAttribute("hotel", ctx.hotel());
        m.addAttribute("todayTotal", d.get("todayTotal"));
        m.addAttribute("todaySlots", d.get("todaySlots"));
        m.addAttribute("recent", d.get("recent"));
        m.addAttribute("canConfirm", ctx.canConfirmPayments());
        if (ctx.canConfirmPayments()) m.addAttribute("pendingPayments", queries.pendingPayments(ctx.hotelId()));
        m.addAttribute("section", "dashboard");
        return "staff/dashboard";
    }

    @GetMapping("/customer/dashboard")
    public String customer(Model m) {
        AppUserDetails u = CurrentUser.require();
        var mySubs = customers.subscriptions(u.getId(), null);
        m.addAttribute("subs", mySubs);
        m.addAttribute("menuByHotel", menus.todayByHotel(mySubs.stream().filter(s -> "ACTIVE".equals(s.getStatus()))
                .map(com.mealtoken.service.CustomerService.SubView::getHotelId).toList()));
        m.addAttribute("recent", customers.recentAttendance(u.getId(), 5));
        m.addAttribute("section", "dashboard");
        return "customer/dashboard";
    }
}
