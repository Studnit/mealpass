package com.mealtoken.service;

import com.mealtoken.entity.MenuItem;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.support.AbstractIntegrationTest;
import com.mealtoken.support.Fixtures;
import com.mealtoken.support.Fixtures.World;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MenuServiceIT extends AbstractIntegrationTest {
    @Autowired Fixtures fx;
    @Autowired MenuService menu;
    @Autowired JdbcTemplate jdbc;

    private long notifications(Long user, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND type = ?", Long.class, user, type);
    }

    @Test
    void itemsAreGroupedWholeDayFirstThenBySlotInTimeOrder() {
        World w = fx.newWorld();
        LocalDate today = menu.today(w.hotel());
        menu.add(w.owner(), today, w.dinner().getId(), "Paneer", null, true);
        menu.add(w.owner(), today, w.lunch().getId(), "Dal Rice", "with papad", true);
        menu.add(w.owner(), today, null, "Buttermilk", null, true);

        List<MenuService.Group> g = menu.groups(w.hotel().getId(), today);
        assertThat(g).extracting(MenuService.Group::getSlotName).containsExactly(null, "Lunch", "Dinner");
        assertThat(g.get(1).getItems().get(0).getDescription()).isEqualTo("with papad");
        assertThat(menu.groups(w.hotel().getId(), today.plusDays(1))).isEmpty();
    }

    @Test
    void soldOutToggleAndDelete() {
        World w = fx.newWorld();
        LocalDate today = menu.today(w.hotel());
        MenuItem m = menu.add(w.owner(), today, null, "Roti", null, true);
        menu.toggleAvailable(w.owner(), m.getId());
        assertThat(menu.groups(w.hotel().getId(), today).get(0).getItems().get(0).isAvailable()).isFalse();
        menu.toggleAvailable(w.owner(), m.getId());
        assertThat(menu.groups(w.hotel().getId(), today).get(0).getItems().get(0).isAvailable()).isTrue();
        menu.delete(w.owner(), m.getId());
        assertThat(menu.groups(w.hotel().getId(), today)).isEmpty();
    }

    @Test
    void anotherHotelsOwnerCannotTouchThisMenu_andCannotUseItsSlots() {
        World a = fx.newWorld(), b = fx.newWorld();
        LocalDate today = menu.today(a.hotel());
        MenuItem m = menu.add(a.owner(), today, null, "Secret thali", null, true);
        assertThatThrownBy(() -> menu.toggleAvailable(b.owner(), m.getId())).hasMessage("error.notfound");
        assertThatThrownBy(() -> menu.delete(b.owner(), m.getId())).hasMessage("error.notfound");
        assertThatThrownBy(() -> menu.add(b.owner(), menu.today(b.hotel()), a.lunch().getId(), "x", null, true)).hasMessage("error.slot.invalid");
        assertThat(menu.groups(b.hotel().getId(), today)).isEmpty();
    }

    @Test
    void invalidInputAndDatesOutsideTheWindowAreRejected() {
        World w = fx.newWorld();
        LocalDate today = menu.today(w.hotel());
        assertThatThrownBy(() -> menu.add(w.owner(), today, null, "  ", null, true)).hasMessage("error.menu.name");
        assertThatThrownBy(() -> menu.add(w.owner(), today, null, "x".repeat(121), null, true)).hasMessage("error.menu.name");
        assertThatThrownBy(() -> menu.add(w.owner(), today.minusDays(1), null, "Old", null, true)).hasMessage("error.menu.date");
        assertThatThrownBy(() -> menu.add(w.owner(), today.plusDays(8), null, "Far", null, true)).hasMessage("error.menu.date");
        menu.add(w.owner(), today.plusDays(7), null, "Planned", null, true);   // last allowed day
    }

    @Test
    void copyingAnotherDayAddsOnlyMissingItems() {
        World w = fx.newWorld();
        LocalDate today = menu.today(w.hotel());
        menu.add(w.owner(), today, w.lunch().getId(), "Dal", null, true);
        menu.add(w.owner(), today, w.lunch().getId(), "Rice", null, true);
        assertThat(menu.copy(w.owner(), today, today.plusDays(1))).isEqualTo(2);
        assertThat(menu.copy(w.owner(), today, today.plusDays(1))).isZero();
        assertThat(menu.groups(w.hotel().getId(), today.plusDays(1)).get(0).getItems()).hasSize(2);
    }

    @Test
    void notificationGoesToActiveSubscribersOnly_andOnlyOncePerDay() {
        World w = fx.newWorld();
        Long subscriber = fx.newCustomer(), outsider = fx.newCustomer();
        fx.activeSubscription(w, subscriber, w.planA());
        LocalDate today = menu.today(w.hotel());

        assertThatThrownBy(() -> menu.notifyCustomers(w.owner(), today)).hasMessage("error.menu.empty");
        menu.add(w.owner(), today, null, "Thali", null, true);
        assertThat(menu.notifyCustomers(w.owner(), today)).isEqualTo(1);
        menu.notifyCustomers(w.owner(), today);                       // pressed twice

        assertThat(notifications(subscriber, "MENU")).isEqualTo(1);
        assertThat(notifications(outsider, "MENU")).isZero();
    }
}
