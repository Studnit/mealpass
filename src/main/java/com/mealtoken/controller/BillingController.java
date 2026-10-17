package com.mealtoken.controller;

import com.mealtoken.security.AccessService;
import com.mealtoken.security.AppUserDetails;
import com.mealtoken.security.CurrentUser;
import com.mealtoken.security.HotelContext;
import com.mealtoken.service.BillingService;
import com.mealtoken.service.PaymentService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;

/** Billing screens: what each subscription costs, what has been paid and what is still due. */
@Controller
public class BillingController {
    private final BillingService billing;
    private final PaymentService payments;
    private final AccessService access;

    public BillingController(BillingService billing, PaymentService payments, AccessService access) {
        this.billing = billing; this.payments = payments; this.access = access;
    }

    @GetMapping("/customer/billing")
    public String customer(Model m) {
        AppUserDetails u = CurrentUser.require();
        var lines = new java.util.ArrayList<>(billing.customerLines(u.getId()));
        lines.sort(java.util.Comparator.comparing(BillingService.Line::isSettled));   // money still due first
        m.addAttribute("lines", lines);
        m.addAttribute("pendingCount", lines.stream().filter(l -> !l.isSettled()).count());
        m.addAttribute("totalPaid", lines.stream().map(BillingService.Line::getPaid).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.addAttribute("totalDue", lines.stream().map(BillingService.Line::getDue).reduce(BigDecimal.ZERO, BigDecimal::add));
        m.addAttribute("receipts", billing.customerReceipts(u.getId(), 20));
        m.addAttribute("ledger", billing.ledger(lines.stream().map(BillingService.Line::getSubscriptionId).toList()));
        m.addAttribute("section", "billing");
        return "customer/billing";
    }

    @GetMapping("/owner/billing")
    public String owner(Model m) {
        HotelContext ctx = access.current();
        m.addAttribute("hotel", ctx.hotel());
        m.addAttribute("summary", billing.hotelSummary(ctx.hotelId(), ctx.hotel().getTimezone()));
        var lines = new java.util.ArrayList<>(billing.hotelLines(ctx.hotelId()));
        lines.sort(java.util.Comparator.comparing(BillingService.Line::isSettled));
        m.addAttribute("lines", lines);
        m.addAttribute("pendingCount", lines.stream().filter(l -> !l.isSettled()).count());
        m.addAttribute("ledger", billing.ledger(lines.stream().map(BillingService.Line::getSubscriptionId).toList()));
        m.addAttribute("section", "billing");
        return "owner/billing";
    }

    @PostMapping("/owner/billing/{subscriptionId}/receive")
    public String receive(@PathVariable Long subscriptionId, @RequestParam BigDecimal amount, @RequestParam String method,
                          @RequestParam(required = false) String reference,
                          @RequestParam(defaultValue = "false") boolean activateNow, RedirectAttributes ra) {
        payments.receive(access.currentActive(), subscriptionId, amount, method, reference, activateNow);
        ra.addFlashAttribute("ok", "ok.payment.confirmed");
        return "redirect:/owner/billing";
    }
}
