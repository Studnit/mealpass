package com.mealtoken.support;

import com.mealtoken.dto.RegistrationForm;
import com.mealtoken.entity.*;
import com.mealtoken.repository.*;
import com.mealtoken.security.HotelContext;
import com.mealtoken.service.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Builds a complete, isolated hotel with owner, plans and customers through the real services. */
@Component
public class Fixtures {
    public static final String PASSWORD = "Fixture#2026pw";

    public record World(Hotel hotel, HotelContext owner, SubscriptionPlan planA, SubscriptionPlan planB,
                        MealSlot lunch, MealSlot dinner) {}

    private final HotelService hotelService;
    private final PlanService planService;
    private final RegistrationService registration;
    private final SubscriptionService subscriptions;
    private final PaymentService payments;
    private final HotelRepository hotels;
    private final SubscriptionPlanRepository plans;
    private final MealSlotRepository slots;
    private final SubscriptionRepository subs;

    public Fixtures(HotelService hotelService, PlanService planService, RegistrationService registration,
                    SubscriptionService subscriptions, PaymentService payments, HotelRepository hotels,
                    SubscriptionPlanRepository plans, MealSlotRepository slots, SubscriptionRepository subs) {
        this.hotelService = hotelService; this.planService = planService; this.registration = registration;
        this.subscriptions = subscriptions; this.payments = payments; this.hotels = hotels; this.plans = plans;
        this.slots = slots; this.subs = subs;
    }

    public static String unique(String prefix) { return prefix + "-" + UUID.randomUUID().toString().substring(0, 8); }

    /** Active hotel with Plan A (1 meal/day, 30 tokens) and Plan B (2 meals/day, 60 tokens), lunch + dinner slots. */
    public World newWorld() {
        RegistrationForm f = new RegistrationForm();
        f.setHotelName(unique("Hotel"));
        f.setFullName("Owner");
        f.setEmail(unique("owner") + "@test.local");
        f.setPassword(PASSWORD);
        Hotel h = hotelService.createByAdmin(null, f);
        h = hotels.findById(h.getId()).orElseThrow();
        HotelContext ctx = new HotelContext(h, h.getOwnerId(), true, true);

        SubscriptionPlan a = null, b = null;
        for (SubscriptionPlan p : plans.findByHotelIdOrderByPrice(h.getId())) {
            SubscriptionPlan saved = planService.save(ctx, p.getId(), p.getPlanName(), p.getMealsPerDay(), p.getTotalTokens(),
                    new BigDecimal("100.00"), 30, true, true);
            if (saved.getMealsPerDay() == 1) a = saved; else b = saved;
        }
        List<MealSlot> ms = slots.findByHotelIdAndActiveTrueOrderByStartTime(h.getId());
        return new World(h, ctx, a, b, ms.get(0), ms.get(1));
    }

    public Long newCustomer() {
        RegistrationForm c = new RegistrationForm();
        c.setAccountType("CUSTOMER");
        c.setFullName("Customer");
        c.setEmail(unique("cust") + "@test.local");
        c.setPassword(PASSWORD);
        return registration.register(c, "en").getId();
    }

    /** request -> owner approval -> cash payment confirmed -> ACTIVE subscription. */
    public Subscription activeSubscription(World w, Long customerId, SubscriptionPlan plan) {
        SubscriptionRequest r = subscriptions.request(customerId, w.hotel().getId(), plan.getId());
        Subscription s = subscriptions.approve(w.owner(), r.getId());
        payments.confirmManual(w.owner(), payments.findPendingIdFor(s.getId()), "CASH", null);
        return subs.findById(s.getId()).orElseThrow();
    }
}
