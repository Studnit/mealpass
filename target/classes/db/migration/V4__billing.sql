-- Billing: the amount a subscription costs is fixed when the owner approves it, so that later plan price changes
-- never alter what a customer owes. Paid = sum of SUCCESS payments, remaining = total - paid (partial payments allowed).
ALTER TABLE subscriptions ADD COLUMN total_amount NUMERIC(10,2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0);

UPDATE subscriptions s
   SET total_amount = COALESCE((SELECT SUM(p.amount) FROM payments p
                                 WHERE p.subscription_id = s.id AND p.status IN ('PENDING', 'SUCCESS')), 0);

CREATE INDEX idx_pay_subscription ON payments(subscription_id, status);
