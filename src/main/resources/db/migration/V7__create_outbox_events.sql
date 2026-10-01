CREATE TABLE outbox_events (
    id              BIGSERIAL   PRIMARY KEY,
    event_id        UUID        NOT NULL,
    event_type      VARCHAR(64) NOT NULL,
    aggregate_type  VARCHAR(32) NOT NULL,
    aggregate_id    VARCHAR(64) NOT NULL,
    payload         JSONB       NOT NULL,
    status          VARCHAR(16) NOT NULL,
    correlation_id  VARCHAR(64),
    created_at      TIMESTAMPTZ NOT NULL,
    published_at    TIMESTAMPTZ,
    retry_count     INTEGER     NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      TEXT,
    CONSTRAINT uk_outbox_events_event_id UNIQUE (event_id),
    CONSTRAINT ck_outbox_events_status CHECK (status IN ('PENDING', 'PUBLISHED')),
    CONSTRAINT ck_outbox_events_retry_count_non_negative CHECK (retry_count >= 0),
    CONSTRAINT ck_outbox_events_published_at CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);

-- The publisher polls "PENDING and due" rows; published history is excluded from the index.
CREATE INDEX ix_outbox_events_pending ON outbox_events (next_attempt_at, id) WHERE status = 'PENDING';
