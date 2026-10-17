-- While a subscription still has money due, the last N tokens stay on hold until it is fully paid. 0 turns the rule off.
ALTER TABLE hotels ADD COLUMN token_hold_count INT NOT NULL DEFAULT 5 CHECK (token_hold_count BETWEEN 0 AND 30);
