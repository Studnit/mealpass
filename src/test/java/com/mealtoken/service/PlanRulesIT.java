package com.mealtoken.service;

import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.Fixtures.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanRulesIT extends AbstractIntegrationTest {
    @Autowired Fixtures fx;
    @Autowired PlanService planService;
    @Autowired MenuService menu;
    @Autowired CustomerService customers;

    @Test
    void plansAllowOnlyOneTimeOrTwoTimeMeals() {
        World w = fx.newWorld();
        for (int bad : new int[]{0, 3, 4})
            assertThatThrownBy(() -> planService.save(w.owner(), null, "Bad", bad, 30, BigDecimal.TEN, 30, true, true))
                    .hasMessage("error.plan.invalid");
        assertThat(planService.save(w.owner(), null, "One time", 1, 30, BigDecimal.TEN, 30, true, true).getMealsPerDay()).isEqualTo(1);
        assertThat(planService.save(w.owner(), null, "Two time", 2, 60, BigDecimal.TEN, 30, true, true).getMealsPerDay()).isEqualTo(2);
    }

    @Test
    void todaysMenuIsAvailableForEachHotelTheCustomerSubscribesTo() {
        World a = fx.newWorld(), b = fx.newWorld();
        menu.add(a.owner(), menu.today(a.hotel()), a.lunch().getId(), "Dal Rice", null, true);
        var byHotel = menu.todayByHotel(List.of(a.hotel().getId(), b.hotel().getId()));
        assertThat(byHotel.get(a.hotel().getId())).hasSize(1);
        assertThat(byHotel.get(b.hotel().getId())).isEmpty();
    }
}
