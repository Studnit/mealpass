package com.mealtoken.controller;

import com.mealtoken.security.AccessService;
import com.mealtoken.security.HotelContext;
import com.mealtoken.service.PaymentService;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Cash / UPI verification by the owner or by staff the owner has authorised. */
@Controller
public class PaymentActionController {
    private final PaymentService payments;
    private final AccessService access;

    public PaymentActionController(PaymentService payments, AccessService access) { this.payments = payments; this.access = access; }

    @PostMapping("/{area:owner|staff}/payments/{id}/confirm")
    public String confirm(@PathVariable String area, @PathVariable Long id, @RequestParam String method,
                          @RequestParam(required = false) String reference,
                          @RequestParam(required = false) java.math.BigDecimal amount,
                          @RequestParam(defaultValue = "false") boolean activateNow, RedirectAttributes ra) {
        HotelContext ctx = access.currentActive();
        if (amount == null) payments.confirmManual(ctx, id, method, reference);
        else payments.confirmManual(ctx, id, method, reference, amount, activateNow);
        ra.addFlashAttribute("ok", "ok.payment.confirmed");
        return "redirect:/" + area + "/table/payments";
    }

    @PostMapping("/{area:owner|staff}/payments/{id}/reject")
    public String reject(@PathVariable String area, @PathVariable Long id, RedirectAttributes ra) {
        HotelContext ctx = access.currentActive();
        payments.rejectManual(ctx, id);
        ra.addFlashAttribute("ok", "ok.payment.rejected");
        return "redirect:/" + area + "/table/payments";
    }
}
