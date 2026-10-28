-- Terminal deliveries leave the live registry so framework restart/staleness recovery cannot retry them.
CREATE TABLE event_publication_dead_letters (
    id uuid PRIMARY KEY,
    listener_id text NOT NULL,
    event_type text NOT NULL,
    serialized_event text NOT NULL,
    publication_date timestamptz NOT NULL,
    completion_attempts integer NOT NULL,
    last_resubmission_date timestamptz,
    failed_at timestamptz NOT NULL,
    status varchar(10) NOT NULL CHECK (status='DEAD'),
    reason text NOT NULL
);
CREATE INDEX idx_outbox_dead_failed_at ON event_publication_dead_letters(failed_at);
