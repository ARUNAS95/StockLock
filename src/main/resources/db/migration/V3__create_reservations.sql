CREATE TABLE reservations (
    id             BIGSERIAL   PRIMARY KEY,
    reservation_id UUID        NOT NULL,
    product_id     BIGINT      NOT NULL,
    user_id        VARCHAR(64) NOT NULL,
    quantity       INTEGER     NOT NULL,
    status         VARCHAR(16) NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_reservations_reservation_id UNIQUE (reservation_id),
    CONSTRAINT fk_reservations_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_reservations_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_reservations_status CHECK (status IN ('ACTIVE', 'CONFIRMED', 'EXPIRED', 'CANCELLED'))
);

CREATE INDEX ix_reservations_product ON reservations (product_id);

-- The expiration sweeper only ever looks at ACTIVE rows ordered by expiry; a partial index keeps
-- that scan proportional to the number of live reservations, not the whole history.
CREATE INDEX ix_reservations_active_expires_at ON reservations (expires_at) WHERE status = 'ACTIVE';
