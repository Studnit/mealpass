package com.mealtoken.controller;

import com.mealtoken.audit.AuditService;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.MealSlotRepository;
import com.mealtoken.security.AccessService;
import com.mealtoken.security.HotelContext;
import com.mealtoken.security.RequestRateLimiter;
import com.mealtoken.service.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Staff-operated verification for customers who cannot scan the hotel QR themselves. Identity comes from
 * the customer's short-lived signed pass (or a manual, audited lookup), never from possession of the hotel QR.
 * The verified customer is kept in the server session, so customer ids never appear in URLs.
 */
@Controller
@RequestMapping("/{area:owner|staff}/verify")
public class VerifyController {
    private static final String SESSION_KEY = "verifiedCustomer";
    private static final Duration TTL = Duration.ofMinutes(5);

    private record Verified(Long customerId, Long hotelId, long expiresAtMillis) {}

    private final AccessService access;
    private final PassTokenService passes;
    private final OwnerQueryService queries;
    private final MealMetaService meta;
    private final RedemptionService redemption;
    private final RequestRateLimiter limiter;
    private final AuditService audit;

    public VerifyController(AccessService access, PassTokenService passes, OwnerQueryService queries, MealMetaService meta,
                            RedemptionService redemption, RequestRateLimiter limiter, AuditService audit) {
        this.access = access; this.passes = passes; this.queries = queries; this.meta = meta;
        this.redemption = redemption; this.limiter = limiter; this.audit = audit;
    }

    @GetMapping
    public String page(@PathVariable String area, Model m) {
        HotelContext ctx = access.currentActive();
        m.addAttribute("area", area);
        m.addAttribute("hotel", ctx.hotel());
        m.addAttribute("section", "verify");
        return "shared/verify";
    }

    @PostMapping("/lookup")
    public String lookup(@PathVariable String area, @RequestParam(required = false) String token,
                         @RequestParam(required = false) String identifier, HttpSession session) {
        HotelContext ctx = access.currentActive();
        if (!limiter.tryAcquire("verify:" + ctx.userId(), 30, 60_000)) throw new BusinessException("error.rate");
        Long customerId;
        if (token != null && !token.isBlank()) {
            customerId = passes.verify(token);
        } else {
            customerId = queries.findCustomerId(identifier).orElseThrow(() -> new BusinessException("error.customer.notfound"));
            audit.log(ctx.userId(), ctx.hotelId(), "MANUAL_CUSTOMER_LOOKUP", "User", customerId, null);
        }
        session.setAttribute(SESSION_KEY, new Verified(customerId, ctx.hotelId(), System.currentTimeMillis() + TTL.toMillis()));
        return "redirect:/" + area + "/verify/customer";
    }

    @GetMapping("/customer")
    public String customer(@PathVariable String area, HttpSession session, Model m) {
        HotelContext ctx = access.currentActive();
        Verified v = current(session, ctx);
        List<Map<String, Object>> info = queries.customerAtHotel(v.customerId(), ctx.hotelId());
        if (info.isEmpty()) throw new BusinessException("error.customer.notfound");
        m.addAttribute("info", info.get(0));
        m.addAttribute("slots", meta.activeSlots(ctx.hotelId()));
        m.addAttribute("servedSlotIds", meta.servedSlotIdsToday(v.customerId(), ctx.hotel()));
        m.addAttribute("area", area);
        m.addAttribute("hotel", ctx.hotel());
        m.addAttribute("key", UUID.randomUUID().toString());
        m.addAttribute("section", "verify");
        return "shared/verify-customer";
    }

    @PostMapping("/redeem")
    public String redeem(@PathVariable String area, @RequestParam Long slotId, @RequestParam String key,
                         HttpSession session, RedirectAttributes ra) {
        HotelContext ctx = access.currentActive();
        if (!limiter.tryAcquire("redeem:" + ctx.userId(), 20, 60_000)) throw new BusinessException("error.rate");
        Verified v = current(session, ctx);
        RedemptionResult r = redemption.redeem(new RedeemCommand(v.customerId(), ctx.hotelId(), slotId, ctx.userId(),
                "staff-" + ctx.userId() + "-" + key, ctx.owner() ? "OWNER_VERIFY" : "STAFF_VERIFY"));
        session.removeAttribute(SESSION_KEY);
        ra.addFlashAttribute("ok", "ok.redeemed");
        ra.addFlashAttribute("receiptRef", r.referenceNumber());
        ra.addFlashAttribute("receiptMeal", r.mealName());
        ra.addFlashAttribute("receiptRemaining", r.remainingTokens());
        return "redirect:/" + area + "/verify";
    }

    private Verified current(HttpSession session, HotelContext ctx) {
        Object o = session.getAttribute(SESSION_KEY);
        if (o instanceof Verified v && v.hotelId().equals(ctx.hotelId()) && v.expiresAtMillis() > System.currentTimeMillis()) return v;
        session.removeAttribute(SESSION_KEY);
        throw new BusinessException("error.pass.expired");
    }
}
