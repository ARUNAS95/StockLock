CREATE TABLE orders (
    id           BIGSERIAL      PRIMARY KEY,
    order_id     UUID           NOT NULL,
    user_id      VARCHAR(64)    NOT NULL,
    status       VARCHAR(16)    NOT NULL,
    total_amount NUMERIC(14, 2) NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL,
    updated_at   TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_orders_order_id UNIQUE (order_id),
    CONSTRAINT ck_orders_status CHECK (status IN ('CREATED', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_orders_total_non_negative CHECK (total_amount >= 0)
);

CREATE INDEX ix_orders_user ON orders (user_id);
