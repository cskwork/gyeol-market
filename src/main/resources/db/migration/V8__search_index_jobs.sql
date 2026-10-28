-- Search owns its queue; deleted catalog products must still be removable from the projection.
CREATE TABLE search_index_jobs (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id bigint NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RETRY', 'COMPLETED', 'DEAD')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_error varchar(256),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_search_index_jobs_ready ON search_index_jobs(next_attempt_at, id)
    WHERE status IN ('QUEUED', 'RETRY');
