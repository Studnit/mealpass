-- Where a hotel is, so customers can find hotels by city and area.
ALTER TABLE hotels ADD COLUMN city VARCHAR(80);
ALTER TABLE hotels ADD COLUMN area VARCHAR(80);
CREATE INDEX idx_hotels_city_area ON hotels (lower(city), lower(area)) WHERE status = 'ACTIVE';

-- Email / mobile verification. Existing accounts are treated as verified.
ALTER TABLE users ADD COLUMN verified_at TIMESTAMPTZ;
UPDATE users SET verified_at = created_at;

CREATE TABLE otp_codes (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash   VARCHAR(64) NOT NULL,
    attempts    INT         NOT NULL DEFAULT 0,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_user ON otp_codes(user_id, id DESC);
