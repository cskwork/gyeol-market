-- Modulith 2.1.2 JDBC v2 schema. Flyway owns schema creation.
CREATE TABLE IF NOT EXISTS event_publication
(
  id                     UUID NOT NULL,
  listener_id            TEXT NOT NULL,
  event_type             TEXT NOT NULL,
  serialized_event       TEXT NOT NULL,
  publication_date       TIMESTAMP WITH TIME ZONE NOT NULL,
  completion_date        TIMESTAMP WITH TIME ZONE,
  status                 TEXT,
  completion_attempts    INT,
  last_resubmission_date TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (id)
);
CREATE INDEX IF NOT EXISTS event_publication_serialized_event_hash_idx ON event_publication USING hash(serialized_event);
CREATE INDEX IF NOT EXISTS event_publication_by_completion_date_idx ON event_publication (completion_date);

ALTER TABLE members ADD COLUMN point_balance numeric(19,0) NOT NULL DEFAULT 5000;
ALTER TABLE members ADD COLUMN reserved_points numeric(19,0) NOT NULL DEFAULT 0;
ALTER TABLE members ADD CONSTRAINT member_points_valid CHECK (point_balance >= reserved_points AND reserved_points >= 0);
ALTER TABLE product ADD COLUMN status varchar(20) NOT NULL DEFAULT 'ON_SALE';
CREATE TABLE rate_limit_buckets (id bigint PRIMARY KEY, state bytea, expires_at bigint);
CREATE INDEX rate_limit_expiry ON rate_limit_buckets(expires_at);
