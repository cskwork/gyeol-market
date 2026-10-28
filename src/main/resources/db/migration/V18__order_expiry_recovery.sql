ALTER TABLE orders ADD COLUMN expiry_attempts integer NOT NULL DEFAULT 0 CHECK (expiry_attempts >= 0);
ALTER TABLE orders ADD COLUMN expiry_next_attempt_at timestamptz;
ALTER TABLE orders ADD COLUMN expiry_last_error varchar(200);
CREATE INDEX idx_orders_expiry_retry ON orders(expiry_next_attempt_at,created_at,id)
    WHERE status='PAYMENT_PENDING';

-- No foreign key: reconciliation may commit independently while the caller owns an order row lock.
CREATE TABLE order_reconciliation_holds (
    order_id bigint PRIMARY KEY CHECK (order_id > 0),
    reason varchar(200) NOT NULL,
    created_at timestamptz NOT NULL
);
