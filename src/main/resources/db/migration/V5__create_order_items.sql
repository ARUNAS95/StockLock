CREATE TABLE order_items (
    id             BIGSERIAL      PRIMARY KEY,
    order_id       BIGINT         NOT NULL,
    product_id     BIGINT         NOT NULL,
    reservation_id UUID           NOT NULL,
    quantity       INTEGER        NOT NULL,
    price          NUMERIC(12, 2) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT fk_order_items_reservation FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id),
    -- A reservation can back at most one order line, so the same held stock can never be sold twice.
    CONSTRAINT uk_order_items_reservation UNIQUE (reservation_id),
    CONSTRAINT ck_order_items_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_order_items_price_non_negative CHECK (price >= 0)
);

CREATE INDEX ix_order_items_order ON order_items (order_id);
