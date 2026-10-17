-- Daily menu: items the hotel offers on a given date (optionally tied to one meal slot).
CREATE TABLE menu_items (
    id           BIGSERIAL PRIMARY KEY,
    hotel_id     BIGINT       NOT NULL REFERENCES hotels(id) ON DELETE CASCADE,
    menu_date    DATE         NOT NULL,
    meal_slot_id BIGINT       REFERENCES meal_slots(id) ON DELETE SET NULL,   -- NULL = served all day
    name         VARCHAR(120) NOT NULL,
    description  VARCHAR(300),
    veg          BOOLEAN      NOT NULL DEFAULT TRUE,
    available    BOOLEAN      NOT NULL DEFAULT TRUE,
    sort_order   INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_menu_hotel_date ON menu_items(hotel_id, menu_date);
