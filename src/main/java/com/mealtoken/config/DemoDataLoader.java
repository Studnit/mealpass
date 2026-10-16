package com.mealtoken.config;

import com.mealtoken.dto.RegistrationForm;
import com.mealtoken.entity.Hotel;
import com.mealtoken.entity.SubscriptionPlan;
import com.mealtoken.repository.HotelRepository;
import com.mealtoken.repository.SubscriptionPlanRepository;
import com.mealtoken.repository.UserRepository;
import com.mealtoken.security.HotelContext;
import com.mealtoken.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * Development sample data (SEED_DEMO_DATA=true). Built through the real services, so it exercises
 * the same approval, payment, activation and ledger rules as production data.
 * All demo accounts use the password Demo#2026pass. Never enable this in production.
 */
@Component
@Order(2)
@ConditionalOnProperty(name = "app.seed-demo-data", havingValue = "true")
public class DemoDataLoader implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataLoader.class);
    static final String PASSWORD = "Demo#2026pass";

    private final UserRepository users;
    private final HotelRepository hotels;
    private final SubscriptionPlanRepository plans;
    private final HotelService hotelService;
    private final PlanService planService;
    private final StaffService staffService;
    private final RegistrationService registration;
    private final SubscriptionService subscriptions;
    private final PaymentService payments;

    public DemoDataLoader(UserRepository users, HotelRepository hotels, SubscriptionPlanRepository plans, HotelService hotelService,
                          PlanService planService, StaffService staffService, RegistrationService registration,
                          SubscriptionService subscriptions, PaymentService payments) {
        this.users = users; this.hotels = hotels; this.plans = plans; this.hotelService = hotelService;
        this.planService = planService; this.staffService = staffService; this.registration = registration;
        this.subscriptions = subscriptions; this.payments = payments;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (users.existsByEmailIgnoreCase("owner@demo.local")) return;

        RegistrationForm f = new RegistrationForm();
        f.setHotelName("Annapurna Mess");
        f.setFullName("Demo Owner");
        f.setEmail("owner@demo.local");
        f.setPhone("9000000001");
        f.setPassword(PASSWORD);
        Hotel h = hotelService.createByAdmin(null, f);
        Long ownerId = h.getOwnerId();
        HotelContext ctx = new HotelContext(hotels.findById(h.getId()).orElseThrow(), ownerId, true, true);

        for (SubscriptionPlan p : plans.findByHotelIdOrderByPrice(h.getId())) {
            boolean one = p.getMealsPerDay() == 1;
            planService.save(ctx, p.getId(), p.getPlanName(), p.getMealsPerDay(), p.getTotalTokens(),
                    new BigDecimal(one ? "2400" : "4500"), p.getValidityDays(), true, true);
        }
        staffService.create(ctx, "Demo Staff", "staff@demo.local", "9000000002", PASSWORD, true);

        List<SubscriptionPlan> active = plans.findByHotelIdAndActiveTrueOrderByPrice(h.getId());
        String[][] customers = {{"Asha Patil", "asha@demo.local", "9000000011"}, {"Ravi Kumar", "ravi@demo.local", "9000000012"}};
        for (int i = 0; i < customers.length; i++) {
            RegistrationForm c = new RegistrationForm();
            c.setAccountType("CUSTOMER");
            c.setFullName(customers[i][0]); c.setEmail(customers[i][1]); c.setPhone(customers[i][2]); c.setPassword(PASSWORD);
            Long customerId = registration.register(c, "en").getId();
            var request = subscriptions.request(customerId, h.getId(), active.get(i % active.size()).getId());
            var sub = subscriptions.approve(ctx, request.getId());
            payments.confirmManual(ctx, payments.findPendingIdFor(sub.getId()), "CASH", null);
        }
        log.warn("Demo data created. Sign in as owner@demo.local / staff@demo.local / asha@demo.local with password {}", PASSWORD);
    }
}
