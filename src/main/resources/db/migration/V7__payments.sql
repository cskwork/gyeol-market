-- Payment owns its deduplication and asynchronous projections without cross-module foreign keys.
CREATE TABLE payment_confirmations (
    payment_key varchar(200) PRIMARY KEY,
    order_id bigint NOT NULL UNIQUE,
    order_no varchar(100) NOT NULL,
    member_id bigint NOT NULL,
    amount numeric(19,2) NOT NULL CHECK (amount >= 0),
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'PAID')),
    created_at timestamptz NOT NULL,
    confirmed_at timestamptz
);
CREATE TABLE payment_webhook_events (
    event_id varchar(200) PRIMARY KEY,
    payload_hash varchar(64) NOT NULL,
    processed_at timestamptz NOT NULL
);
CREATE TABLE payment_intents (
    order_id bigint PRIMARY KEY, order_no varchar(100) NOT NULL,
    member_id bigint NOT NULL, placed_at timestamptz NOT NULL
);
CREATE TABLE payment_audit (
    payment_key varchar(200) PRIMARY KEY, order_id bigint NOT NULL,
    order_no varchar(100) NOT NULL, member_id bigint NOT NULL, confirmed_at timestamptz NOT NULL
);
