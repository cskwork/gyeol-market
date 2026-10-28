ALTER TABLE refresh_families ADD COLUMN previous_token_hash varchar(64),
    ADD COLUMN successor_token varchar(256),
    ADD COLUMN successor_expires_at timestamptz;
CREATE INDEX idx_refresh_families_expiry ON refresh_families(expires_at);
CREATE INDEX idx_refresh_families_revocation ON refresh_families(revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX idx_refresh_successor_expiry ON refresh_families(successor_expires_at) WHERE successor_token IS NOT NULL;
