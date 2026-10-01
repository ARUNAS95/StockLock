-- The UNIQUE constraint on idempotency_key is what makes idempotency correct under concurrency:
-- two requests racing with the same key serialize on this index, and only one can commit.
CREATE TABLE idempotency_records (
    id              BIGSERIAL    PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    resource_type   VARCHAR(32)  NOT NULL,
    resource_id     UUID         NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_idempotency_records_key UNIQUE (idempotency_key)
);

CREATE INDEX ix_idempotency_records_expires_at ON idempotency_records (expires_at);
