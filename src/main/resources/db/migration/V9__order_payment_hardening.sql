CREATE INDEX idx_orders_pending_expiry ON orders(created_at,id) WHERE status='PAYMENT_PENDING';
ALTER TABLE order_items DROP COLUMN option_status, DROP COLUMN available_quantity;
DROP TABLE payment_intents;
DROP TABLE payment_audit;
ALTER TABLE payment_webhook_events ADD COLUMN status varchar(20) NOT NULL DEFAULT 'PROCESSED'
    CHECK (status IN ('RECEIVED','PROCESSED','NEEDS_REVIEW'));
ALTER TABLE payment_webhook_events ADD COLUMN review_reason varchar(200);
ALTER TABLE orders ALTER COLUMN idempotency_key DROP NOT NULL, ALTER COLUMN canonical_request DROP NOT NULL;
CREATE INDEX idx_payment_webhook_retention ON payment_webhook_events(processed_at) WHERE status='PROCESSED';
ALTER TABLE payment_webhook_events ADD COLUMN order_no varchar(100), ADD COLUMN payment_key varchar(200),
    ADD COLUMN amount numeric(19,2), ADD COLUMN event_status varchar(20);
