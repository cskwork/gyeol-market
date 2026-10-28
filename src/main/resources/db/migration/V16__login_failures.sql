CREATE TABLE login_failure_budgets (
    email_hash varchar(64) PRIMARY KEY,
    window_started_at timestamptz NOT NULL,
    failures integer NOT NULL CHECK (failures >= 0),
    blocked_until timestamptz,
    origin_hashes text NOT NULL
);
CREATE INDEX idx_login_failure_retention ON login_failure_budgets(window_started_at);
