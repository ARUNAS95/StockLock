-- Consumer-side de-duplication for at-least-once delivery. A consumer inserts (event_id, consumer)
-- in the same transaction as its side effect; a redelivered event hits the primary key and is skipped.
CREATE TABLE processed_events (
    event_id     UUID         NOT NULL,
    consumer     VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, consumer)
);
