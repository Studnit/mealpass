-- V1: full core schema (all phases build on this)

CREATE TABLE roles (
    id   BIGSERIAL PRIMARY KEY,
    name VARCHAR(30) NOT NULL UNIQUE
);
INSERT INTO roles(name) VALUES ('SUPER_ADMIN'),('OWNER'),('STAFF'),('CUSTOMER');

CREATE TABLE users (
    id                 BIGSERIAL PRIMARY KEY,
    full_name          VARCHAR(150) NOT NULL,
    email              VARCHAR(190) NOT NULL UNIQUE,
    phone              VARCHAR(20),
    password_hash      VARCHAR(100) NOT NULL,
    preferred_language VARCHAR(5)   NOT NULL DEFAULT 'en',
    status             VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    failed_logins      INT          NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_status CHECK (status IN ('ACTIVE','SUSPENDED','DEACTIVATED'))
);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles(id),
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE hotels (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(150) NOT NULL,
    slug       VARCHAR(160) NOT NULL UNIQUE,
    address    VARCHAR(300),
    phone      VARCHAR(20),
    logo_path  VARCHAR(300),
    timezone   VARCHAR(50)  NOT NULL DEFAULT 'Asia/Kolkata',
    status     VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    owner_id   BIGINT       NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_hotel_status CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','DEACTIVATED'))
);
CREATE INDEX idx_hotels_owner ON hotels(owner_id);

CREATE TABLE hotel_staff (
    id       BIGSERIAL PRIMARY KEY,
    hotel_id BIGINT NOT NULL REFERENCES hotels(id) ON DELETE CASCADE,
    user_id  BIGINT NOT NULL REFERENCES users(id),
    active   BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (hotel_id, user_id)
);

CREATE TABLE hotel_qr_codes (
    id                BIGSERIAL PRIMARY KEY,
    hotel_id          BIGINT      NOT NULL REFERENCES hotels(id) ON DELETE CASCADE,
    public_identifier VARCHAR(64) NOT NULL UNIQUE,
    status            VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at        TIMESTAMPTZ,
    CONSTRAINT chk_qr_status CHECK (status IN ('ACTIVE','REVOKED'))
);
CREATE UNIQUE INDEX uq_one_active_qr_per_hotel ON hotel_qr_codes(hotel_id) WHERE status = 'ACTIVE';

CREATE TABLE meal_slots (
    id         BIGSERIAL PRIMARY KEY,
    hotel_id   BIGINT      NOT NULL REFERENCES hotels(id) ON DELETE CASCADE,
    code       VARCHAR(20) NOT NULL,
    name       VARCHAR(60) NOT NULL,
    start_time TIME        NOT NULL,
    end_time   TIME        NOT NULL,
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    UNIQUE (hotel_id, code)
);

CREATE TABLE subscription_plans (
    id             BIGSERIAL PRIMARY KEY,
    hotel_id       BIGINT        NOT NULL REFERENCES hotels(id) ON DELETE CASCADE,
    plan_name      VARCHAR(100)  NOT NULL,
    meals_per_day  INT           NOT NULL CHECK (meals_per_day BETWEEN 1 AND 4),
    total_tokens   INT           NOT NULL CHECK (total_tokens > 0),
    price          NUMERIC(10,2) NOT NULL CHECK (price >= 0),
    validity_days  INT           NOT NULL CHECK (validity_days > 0),
    tokens_expire_with_subscription BOOLEAN NOT NULL DEFAULT TRUE,
    active         BOOLEAN       NOT NULL DEFAULT TRUE
);

CREATE TABLE subscriptions (
    id               BIGSERIAL PRIMARY KEY,
    customer_id      BIGINT      NOT NULL REFERENCES users(id),
    hotel_id         BIGINT      NOT NULL REFERENCES hotels(id),
    plan_id          BIGINT      NOT NULL REFERENCES subscription_plans(id),
    start_date       DATE,
    expiry_date      DATE,
    total_tokens     INT         NOT NULL,
    used_tokens      INT         NOT NULL DEFAULT 0,
    remaining_tokens INT         NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_status   VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT chk_sub_status CHECK (status IN ('PENDING','ACTIVE','EXPIRED','SUSPENDED','CANCELLED','EXHAUSTED')),
    CONSTRAINT chk_tokens_nonneg CHECK (remaining_tokens >= 0 AND used_tokens >= 0),
    CONSTRAINT chk_tokens_sum CHECK (used_tokens + remaining_tokens = total_tokens)
);
CREATE INDEX idx_sub_customer ON subscriptions(customer_id);
CREATE INDEX idx_sub_hotel_status ON subscriptions(hotel_id, status);

CREATE TABLE subscription_requests (
    id          BIGSERIAL PRIMARY KEY,
    customer_id BIGINT      NOT NULL REFERENCES users(id),
    hotel_id    BIGINT      NOT NULL REFERENCES hotels(id),
    plan_id     BIGINT      NOT NULL REFERENCES subscription_plans(id),
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    note        VARCHAR(300),
    decided_by  BIGINT REFERENCES users(id),
    decided_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_req_status CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED'))
);

CREATE TABLE payments (
    id                BIGSERIAL PRIMARY KEY,
    customer_id       BIGINT        NOT NULL REFERENCES users(id),
    hotel_id          BIGINT        NOT NULL REFERENCES hotels(id),
    subscription_id   BIGINT        REFERENCES subscriptions(id),
    amount            NUMERIC(10,2) NOT NULL CHECK (amount >= 0),
    payment_method    VARCHAR(20)   NOT NULL,
    payment_reference VARCHAR(100),
    status            VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    verified_by       BIGINT REFERENCES users(id),
    paid_at           TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_pay_status CHECK (status IN ('PENDING','SUCCESS','FAILED','REFUNDED','CANCELLED'))
);

CREATE TABLE meal_attendance (
    id               BIGSERIAL PRIMARY KEY,
    customer_id      BIGINT      NOT NULL REFERENCES users(id),
    hotel_id         BIGINT      NOT NULL REFERENCES hotels(id),
    subscription_id  BIGINT      NOT NULL REFERENCES subscriptions(id),
    meal_slot_id     BIGINT      NOT NULL REFERENCES meal_slots(id),
    attendance_date  DATE        NOT NULL,
    redeemed_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    status           VARCHAR(20) NOT NULL DEFAULT 'SERVED',
    reference_number VARCHAR(40) NOT NULL UNIQUE,
    idempotency_key  VARCHAR(80) UNIQUE,
    created_by       BIGINT REFERENCES users(id),
    CONSTRAINT chk_att_status CHECK (status IN ('SERVED','REVERSED'))
);
-- one ACTIVE (non-reversed) redemption per subscription / date / slot
CREATE UNIQUE INDEX uq_active_attendance
    ON meal_attendance(subscription_id, attendance_date, meal_slot_id) WHERE status = 'SERVED';

CREATE TABLE token_transactions (
    id               BIGSERIAL PRIMARY KEY,
    subscription_id  BIGINT      NOT NULL REFERENCES subscriptions(id),
    transaction_type VARCHAR(20) NOT NULL,
    amount           INT         NOT NULL,
    balance_before   INT         NOT NULL,
    balance_after    INT         NOT NULL CHECK (balance_after >= 0),
    reference_number VARCHAR(40) NOT NULL,
    reason           VARCHAR(300),
    created_by       BIGINT REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_tx_type CHECK (transaction_type IN ('CREDIT','DEBIT','REVERSAL','EXPIRY','ADJUSTMENT'))
);
-- a reversal may reference the same attendance ref only once
CREATE UNIQUE INDEX uq_reversal_once ON token_transactions(reference_number, transaction_type)
    WHERE transaction_type = 'REVERSAL';

CREATE TABLE notifications (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       VARCHAR(40)  NOT NULL,
    message    VARCHAR(400) NOT NULL,
    read_flag  BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notif_user ON notifications(user_id, read_flag);

CREATE TABLE audit_logs (
    id            BIGSERIAL PRIMARY KEY,
    actor_user_id BIGINT REFERENCES users(id),
    hotel_id      BIGINT REFERENCES hotels(id),
    action        VARCHAR(60) NOT NULL,
    entity_type   VARCHAR(60),
    entity_id     BIGINT,
    details       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_hotel ON audit_logs(hotel_id, created_at);

CREATE TABLE application_settings (
    setting_key   VARCHAR(80) PRIMARY KEY,
    setting_value VARCHAR(500) NOT NULL
);
