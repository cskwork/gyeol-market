-- Reconciliation must survive the rollback of the local payment transaction.
-- No foreign key points at a potentially uncommitted payment confirmation.
CREATE TABLE payment_reconciliation_cases (
    id uuid PRIMARY KEY,
    order_id bigint NOT NULL,
    order_no varchar(100) NOT NULL,
    payment_key varchar(200) NOT NULL,
    amount numeric(19,2) NOT NULL,
    status varchar(20) NOT NULL CHECK (status = 'NEEDS_REVIEW'),
    reason varchar(200) NOT NULL,
    created_at timestamptz NOT NULL
);
CREATE INDEX idx_payment_reconciliation_order ON payment_reconciliation_cases(order_id,created_at);
