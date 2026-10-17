package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.SubscriptionPlan;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.repository.SubscriptionPlanRepository;
import com.mealtoken.security.HotelContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class PlanService {
    private final SubscriptionPlanRepository plans;
    private final AuditService audit;

    public PlanService(SubscriptionPlanRepository plans, AuditService audit) { this.plans = plans; this.audit = audit; }

    /**
     * Creates or updates a plan. Edits never alter existing subscriptions: those keep the tokens and
     * dates they were activated with.
     */
    @Transactional
    public SubscriptionPlan save(HotelContext ctx, Long planId, String name, int mealsPerDay, int totalTokens,
                                 BigDecimal price, int validityDays, boolean tokensExpire, boolean active) {
        if (!ctx.owner()) throw new BusinessException("error.forbidden");
        if (name == null || name.isBlank() || name.length() > 100 || mealsPerDay < 1 || mealsPerDay > 2
                || totalTokens < 1 || totalTokens > 1000 || validityDays < 1 || validityDays > 730
                || price == null || price.signum() < 0 || price.compareTo(new BigDecimal("10000000")) > 0)
            throw new BusinessException("error.plan.invalid");
        SubscriptionPlan p = planId == null ? new SubscriptionPlan()
                : plans.findByIdAndHotelId(planId, ctx.hotelId()).orElseThrow(() -> new BusinessException("error.notfound"));
        p.setHotelId(ctx.hotelId());
        p.setPlanName(name.trim());
        p.setMealsPerDay(mealsPerDay);
        p.setTotalTokens(totalTokens);
        p.setPrice(price.setScale(2, java.math.RoundingMode.HALF_UP));
        p.setValidityDays(validityDays);
        p.setTokensExpireWithSubscription(tokensExpire);
        p.setActive(active);
        p = plans.save(p);
        audit.log(ctx.userId(), ctx.hotelId(), planId == null ? "PLAN_CREATED" : "PLAN_UPDATED", "SubscriptionPlan", p.getId(),
                "tokens=" + totalTokens + " price=" + p.getPrice());
        return p;
    }
}
