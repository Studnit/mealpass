package com.mealtoken.controller;

import com.mealtoken.entity.Hotel;
import com.mealtoken.entity.User;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.notification.NotificationService;
import com.mealtoken.repository.HotelRepository;
import com.mealtoken.repository.MealSlotRepository;
import com.mealtoken.repository.SubscriptionPlanRepository;
import com.mealtoken.repository.UserRepository;
import com.mealtoken.security.AppUserDetails;
import com.mealtoken.security.CurrentUser;
import com.mealtoken.security.RequestRateLimiter;
import com.mealtoken.service.*;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Set;
import java.util.UUID;

/** The customer area. The customer id always comes from the session; nothing in a URL or form can select another customer. */
@Controller
@RequestMapping("/customer")
public class CustomerController {
    private final CustomerService customers;
    private final HotelRepository hotels;
    private final SubscriptionPlanRepository plans;
    private final MealSlotRepository slots;
    private final SubscriptionService subscriptions;
    private final PaymentService payments;
    private final QrService qr;
    private final RedemptionService redemption;
    private final PassTokenService passes;
    private final MealMetaService meta;
    private final UserRepository users;
    private final NotificationService notifications;
    private final RequestRateLimiter limiter;
    private final com.mealtoken.service.MenuService menus;
    private final com.mealtoken.notification.VoiceService voice;

    public CustomerController(CustomerService customers, HotelRepository hotels, SubscriptionPlanRepository plans,
                              MealSlotRepository slots, SubscriptionService subscriptions, PaymentService payments,
                              QrService qr, RedemptionService redemption, PassTokenService passes, MealMetaService meta,
                              UserRepository users, NotificationService notifications, RequestRateLimiter limiter,
                              com.mealtoken.service.MenuService menus,
                              com.mealtoken.notification.VoiceService voice) {
        this.customers = customers; this.hotels = hotels; this.plans = plans; this.slots = slots;
        this.subscriptions = subscriptions; this.payments = payments; this.qr = qr; this.redemption = redemption;
        this.passes = passes; this.meta = meta; this.users = users; this.notifications = notifications; this.limiter = limiter;
        this.menus = menus; this.voice = voice;
    }

    // ------------------------------------------------------------------ discovery & subscribing

    @GetMapping("/hotels")
    public String hotels(@RequestParam(required = false) String q, @RequestParam(required = false) String city,
                         @RequestParam(required = false) String area, jakarta.servlet.http.HttpSession session, Model m) {
        // A customer who arrived through a hotel's app QR starts with that hotel's city and area selected.
        if (city == null && area == null && session.getAttribute(AppController.SESSION_CITY) != null) {
            city = (String) session.getAttribute(AppController.SESSION_CITY);
            area = (String) session.getAttribute(AppController.SESSION_AREA);
        }
        m.addAttribute("hotels", customers.activeHotels(q, city, area));
        var locations = customers.locations();
        m.addAttribute("locations", locations);
        m.addAttribute("cities", locations.stream().map(l -> (String) l.get("city")).distinct().toList());
        m.addAttribute("city", city == null ? "" : city);
        m.addAttribute("area", area == null ? "" : area);
        m.addAttribute("q", q == null ? "" : q);
        m.addAttribute("section", "hotels");
        return "customer/hotels";
    }

    @GetMapping("/hotels/{id}")
    public String hotel(@PathVariable Long id, Model m) {
        AppUserDetails u = CurrentUser.require();
        Hotel h = hotels.findById(id).filter(x -> "ACTIVE".equals(x.getStatus())).orElseThrow(() -> new BusinessException("error.notfound"));
        m.addAttribute("hotel", h);
        m.addAttribute("plans", plans.findByHotelIdAndActiveTrueOrderByPrice(id));
        m.addAttribute("slots", slots.findByHotelIdAndActiveTrueOrderByStartTime(id));
        m.addAttribute("mine", customers.subscriptions(u.getId(), id));
        m.addAttribute("menu", menus.groups(id, menus.today(h)));
        m.addAttribute("section", "hotels");
        return "customer/hotel";
    }

    @PostMapping("/hotels/{id}/request")
    public String request(@PathVariable Long id, @RequestParam Long planId, RedirectAttributes ra) {
        AppUserDetails u = CurrentUser.require();
        subscriptions.request(u.getId(), id, planId);
        ra.addFlashAttribute("ok", "ok.requested");
        hotels.findById(id).ifPresent(h -> ra.addFlashAttribute("speak", voice.say("voice.customer.request", u.getFullName(), h.getName())));
        return "redirect:/customer/subscriptions";
    }

    @GetMapping("/subscriptions")
    public String subscriptions(Model m) {
        AppUserDetails u = CurrentUser.require();
        m.addAttribute("subs", customers.subscriptions(u.getId(), null));
        m.addAttribute("requests", customers.myRequests(u.getId()));
        m.addAttribute("pending", customers.pendingPayments(u.getId()));
        m.addAttribute("section", "subscriptions");
        return "customer/subscriptions";
    }

    @PostMapping("/payments/{id}/upi")
    public String upi(@PathVariable Long id, @RequestParam String reference, RedirectAttributes ra) {
        payments.submitUpiReference(CurrentUser.require().getId(), id, reference);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/customer/subscriptions";
    }

    @GetMapping("/today")
    public String today(Model m) {
        var active = customers.subscriptions(CurrentUser.require().getId(), null).stream()
                .filter(s -> "ACTIVE".equals(s.getStatus())).toList();
        m.addAttribute("subs", active);
        m.addAttribute("menuByHotel", menus.todayByHotel(active.stream().map(com.mealtoken.service.CustomerService.SubView::getHotelId).toList()));
        m.addAttribute("section", "today");
        return "customer/today";
    }

    // ------------------------------------------------------------------ scan & redeem

    @GetMapping("/scan")
    public String scan(Model m) {
        m.addAttribute("section", "scan");
        return "customer/scan";
    }

    /** Landing page of the hotel QR: shows what will be redeemed and asks for explicit confirmation. */
    @GetMapping("/scan/{identifier}")
    public String scanHotel(@PathVariable String identifier, Model m) {
        AppUserDetails u = CurrentUser.require();
        Hotel h = qr.resolveHotel(identifier);
        m.addAttribute("hotel", h);
        m.addAttribute("identifier", identifier);
        m.addAttribute("sub", customers.subscriptions(u.getId(), h.getId()).stream()
                .filter(s -> "ACTIVE".equals(s.getStatus())).findFirst().orElse(null));
        m.addAttribute("plans", plans.findByHotelIdAndActiveTrueOrderByPrice(h.getId()));
        String suggested = customers.suggestedSlotCode(h.getTimezone());
        m.addAttribute("suggested", suggested);
        m.addAttribute("suggestedId", slots.findByHotelIdAndActiveTrueOrderByStartTime(h.getId()).stream()
                .filter(s -> s.getCode().equals(suggested)).map(com.mealtoken.entity.MealSlot::getId).findFirst().orElse(null));
        m.addAttribute("menu", menus.groups(h.getId(), menus.today(h)));
        m.addAttribute("key", UUID.randomUUID().toString()); // idempotency key for this confirmation screen
        m.addAttribute("section", "scan");
        return "customer/redeem";
    }

    @PostMapping("/redeem")
    public String redeem(@RequestParam String identifier, @RequestParam Long slotId, @RequestParam String key,
                         RedirectAttributes ra) {
        AppUserDetails u = CurrentUser.require();
        if (!limiter.tryAcquire("redeem:" + u.getId(), 10, 60_000)) throw new BusinessException("error.rate");
        Hotel h = qr.resolveHotel(identifier);             // re-verified on the server: a stale/revoked QR is rejected
        try {
            RedemptionResult r = redemption.redeem(new RedeemCommand(u.getId(), h.getId(), slotId, u.getId(),
                    "cust-" + u.getId() + "-" + key, "CUSTOMER_SCAN"));
            ra.addFlashAttribute("ok", r.replay() ? "ok.redeemed.replay" : "ok.redeemed");
            ra.addFlashAttribute("speak", voice.say("voice.customer.meal", u.getFullName(), h.getName()));
            return "redirect:/customer/receipt/" + r.referenceNumber();
        } catch (BusinessException e) {
            notifications.notifyIndependent(u.getId(), "REJECTED", "notif.redeem.rejected", h.getName(), "~" + e.getMessage());
            throw e;
        }
    }

    @GetMapping("/receipt/{ref}")
    public String receipt(@PathVariable String ref, Model m) {
        m.addAttribute("r", customers.receipt(CurrentUser.require().getId(), ref).orElseThrow(() -> new BusinessException("error.notfound")));
        m.addAttribute("section", "scan");
        return "customer/receipt";
    }

    // ------------------------------------------------------------------ pass for staff-operated verification

    @GetMapping("/pass")
    public String pass(Model m) {
        m.addAttribute("section", "pass");
        return "customer/pass";
    }

    @GetMapping(value = "/pass/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    @ResponseBody
    public ResponseEntity<byte[]> passImage() {
        String token = passes.issue(CurrentUser.require().getId());
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore()).body(qr.png(token, 360));
    }

    // ------------------------------------------------------------------ profile

    @GetMapping("/profile")
    public String profile(Model m) {
        m.addAttribute("user", users.findById(CurrentUser.require().getId()).orElseThrow());
        m.addAttribute("section", "profile");
        return "customer/profile";
    }

    @PostMapping("/profile")
    public String saveProfile(@RequestParam String fullName, @RequestParam(required = false) String phone,
                              @RequestParam String language, RedirectAttributes ra) {
        if (fullName.isBlank() || fullName.length() > 150 || !Set.of("en", "mr", "hi").contains(language)
                )
            throw new BusinessException("error.input.invalid");
        String cleanPhone = com.mealtoken.util.Validators.mobileOrNull(phone);
        User u = users.findById(CurrentUser.require().getId()).orElseThrow();
        u.setFullName(fullName.trim());
        u.setPhone(cleanPhone);
        u.setPreferredLanguage(language);
        users.save(u);
        ra.addFlashAttribute("ok", "ok.saved");
        return "redirect:/customer/profile?lang=" + language;
    }
}
