package com.mealtoken.service;

import com.mealtoken.audit.AuditService;
import com.mealtoken.entity.Hotel;
import com.mealtoken.entity.MealSlot;
import com.mealtoken.entity.MenuItem;
import com.mealtoken.exception.BusinessException;
import com.mealtoken.notification.NotificationService;
import com.mealtoken.repository.MealSlotRepository;
import com.mealtoken.repository.MenuItemRepository;
import com.mealtoken.security.HotelContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/** The hotel's daily menu. Owners write it; customers read it for the hotel they are at. Always scoped to one hotel. */
@Service
public class MenuService {
    /** Menus can be prepared this many days ahead. */
    static final int DAYS_AHEAD = 7;

    @lombok.Value
    public static class Group { Long slotId; String slotName; List<MenuItem> items; }

    private final MenuItemRepository items;
    private final MealSlotRepository slots;
    private final NotificationService notifications;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final com.mealtoken.repository.HotelRepository hotels;

    public MenuService(MenuItemRepository items, MealSlotRepository slots, NotificationService notifications,
                       AuditService audit, JdbcTemplate jdbc, Clock clock,
                       com.mealtoken.repository.HotelRepository hotels) {
        this.items = items; this.slots = slots; this.notifications = notifications; this.audit = audit;
        this.jdbc = jdbc; this.clock = clock; this.hotels = hotels;
    }

    /** Today in the hotel's own time zone. */
    public LocalDate today(Hotel hotel) { return LocalDate.now(clock.withZone(ZoneId.of(hotel.getTimezone()))); }

    /** Items grouped by meal: the whole-day group first, then one group per slot in time order. Empty groups are dropped. */
    @Transactional(readOnly = true)
    public List<Group> groups(Long hotelId, LocalDate date) {
        List<MenuItem> all = items.findByHotelIdAndMenuDateOrderBySortOrderAscIdAsc(hotelId, date);
        List<Group> out = new ArrayList<>();
        List<MenuItem> wholeDay = all.stream().filter(i -> i.getMealSlotId() == null).toList();
        if (!wholeDay.isEmpty()) out.add(new Group(null, null, wholeDay));
        for (MealSlot s : slots.findByHotelIdOrderByStartTime(hotelId)) {
            List<MenuItem> mine = all.stream().filter(i -> s.getId().equals(i.getMealSlotId())).toList();
            if (!mine.isEmpty()) out.add(new Group(s.getId(), s.getName(), mine));
        }
        return out;
    }

    /** Today's menu (in each hotel's own time zone) for several hotels at once, keyed by hotel id. */
    @Transactional(readOnly = true)
    public Map<Long, List<Group>> todayByHotel(Collection<Long> hotelIds) {
        Map<Long, List<Group>> out = new HashMap<>();
        for (Long id : new LinkedHashSet<>(hotelIds))
            hotels.findById(id).ifPresent(h -> out.put(id, groups(id, today(h))));
        return out;
    }

    @Transactional
    public MenuItem add(HotelContext ctx, LocalDate date, Long slotId, String name, String description, boolean veg) {
        requireEditable(ctx, date);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 120) throw new BusinessException("error.menu.name");
        String d = description == null ? null : description.trim();
        if (d != null && d.isEmpty()) d = null;
        if (d != null && d.length() > 300) throw new BusinessException("error.menu.name");
        if (slotId != null) slots.findByIdAndHotelId(slotId, ctx.hotelId()).orElseThrow(() -> new BusinessException("error.slot.invalid"));
        MenuItem m = new MenuItem();
        m.setHotelId(ctx.hotelId());
        m.setMenuDate(date);
        m.setMealSlotId(slotId);
        m.setName(n);
        m.setDescription(d);
        m.setVeg(veg);
        m.setSortOrder(items.findByHotelIdAndMenuDateOrderBySortOrderAscIdAsc(ctx.hotelId(), date).size());
        m = items.save(m);
        audit.log(ctx.userId(), ctx.hotelId(), "MENU_ITEM_ADDED", "MenuItem", m.getId(), n + " " + date);
        return m;
    }

    /** Mark an item sold out / available again (e.g. when the dish runs out). */
    @Transactional
    public void toggleAvailable(HotelContext ctx, Long id) {
        MenuItem m = items.findByIdAndHotelId(id, ctx.hotelId()).orElseThrow(() -> new BusinessException("error.notfound"));
        requireEditable(ctx, m.getMenuDate());
        m.setAvailable(!m.isAvailable());
        audit.log(ctx.userId(), ctx.hotelId(), "MENU_ITEM_AVAILABILITY", "MenuItem", m.getId(), m.getName() + " available=" + m.isAvailable());
    }

    @Transactional
    public void delete(HotelContext ctx, Long id) {
        MenuItem m = items.findByIdAndHotelId(id, ctx.hotelId()).orElseThrow(() -> new BusinessException("error.notfound"));
        requireEditable(ctx, m.getMenuDate());
        items.delete(m);
        audit.log(ctx.userId(), ctx.hotelId(), "MENU_ITEM_DELETED", "MenuItem", id, m.getName());
    }

    /** Copies the items of another day that are not already on the target day. Returns how many were added. */
    @Transactional
    public int copy(HotelContext ctx, LocalDate from, LocalDate to) {
        requireEditable(ctx, to);
        Set<String> existing = new HashSet<>();
        for (MenuItem i : items.findByHotelIdAndMenuDateOrderBySortOrderAscIdAsc(ctx.hotelId(), to))
            existing.add(i.getMealSlotId() + "|" + i.getName().toLowerCase());
        int added = 0;
        for (MenuItem src : items.findByHotelIdAndMenuDateOrderBySortOrderAscIdAsc(ctx.hotelId(), from)) {
            if (!existing.add(src.getMealSlotId() + "|" + src.getName().toLowerCase())) continue;
            MenuItem m = new MenuItem();
            m.setHotelId(ctx.hotelId()); m.setMenuDate(to); m.setMealSlotId(src.getMealSlotId());
            m.setName(src.getName()); m.setDescription(src.getDescription()); m.setVeg(src.isVeg());
            m.setSortOrder(src.getSortOrder());
            items.save(m);
            added++;
        }
        if (added > 0) audit.log(ctx.userId(), ctx.hotelId(), "MENU_COPIED", "Hotel", ctx.hotelId(), from + " -> " + to + " (" + added + ")");
        return added;
    }

    /**
     * Tells every customer with an ACTIVE subscription at this hotel that the menu is posted. Sent at most once per
     * customer and date, so pressing the button twice does not spam anyone. Returns the number of customers reached.
     */
    @Transactional
    public int notifyCustomers(HotelContext ctx, LocalDate date) {
        requireEditable(ctx, date);
        if (items.findByHotelIdAndMenuDateOrderBySortOrderAscIdAsc(ctx.hotelId(), date).isEmpty())
            throw new BusinessException("error.menu.empty");
        List<Long> customers = jdbc.queryForList(
                "SELECT DISTINCT customer_id FROM subscriptions WHERE hotel_id = ? AND status = 'ACTIVE'", Long.class, ctx.hotelId());
        for (Long c : customers) notifications.notifyOnce(c, "MENU", "notif.menu.posted", ctx.hotel().getName(), date.toString());
        audit.log(ctx.userId(), ctx.hotelId(), "MENU_NOTIFIED", "Hotel", ctx.hotelId(), date + " customers=" + customers.size());
        return customers.size();
    }

    private void requireEditable(HotelContext ctx, LocalDate date) {
        if (date == null) throw new BusinessException("error.menu.date");
        LocalDate today = today(ctx.hotel());
        if (date.isBefore(today) || date.isAfter(today.plusDays(DAYS_AHEAD))) throw new BusinessException("error.menu.date");
    }
}
