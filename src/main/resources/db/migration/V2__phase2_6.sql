-- V2: hotel settings, staff permissions, extra integrity rules, password reset, immutable ledgers

ALTER TABLE hotels
    ADD COLUMN operating_hours      VARCHAR(100),
    ADD COLUMN enforce_meal_window  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN low_token_threshold  INT     NOT NULL DEFAULT 5;

ALTER TABLE hotel_staff
    ADD COLUMN can_confirm_payments BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE payments
    ADD COLUMN receipt_number   VARCHAR(30) UNIQUE,
    ADD COLUMN gateway_provider VARCHAR(30);

-- a customer can hold at most one ACTIVE subscription per hotel (redemption target is unambiguous)
CREATE UNIQUE INDEX uq_one_active_sub ON subscriptions(customer_id, hotel_id) WHERE status = 'ACTIVE';
-- and at most one pending request per hotel
CREATE UNIQUE INDEX uq_one_pending_request ON subscription_requests(customer_id, hotel_id) WHERE status = 'PENDING';

CREATE TABLE password_reset_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE
);

-- the token ledger and audit log are append-only
CREATE FUNCTION forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_token_tx_immutable BEFORE UPDATE OR DELETE ON token_transactions
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER trg_audit_immutable BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION forbid_mutation();

CREATE INDEX idx_att_hotel_date ON meal_attendance(hotel_id, attendance_date);
CREATE INDEX idx_att_sub_date   ON meal_attendance(subscription_id, attendance_date);
CREATE INDEX idx_tx_sub         ON token_transactions(subscription_id, created_at);
CREATE INDEX idx_pay_hotel      ON payments(hotel_id, created_at);
