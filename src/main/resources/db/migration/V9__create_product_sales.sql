-- Read model built by the OrderConfirmed consumer. Its counters would be visibly wrong if a
-- redelivered event were applied twice, which is what makes it a useful idempotency target.
CREATE TABLE product_sales (
    product_id BIGINT         PRIMARY KEY,
    units_sold BIGINT         NOT NULL,
    revenue    NUMERIC(16, 2) NOT NULL,
    updated_at TIMESTAMPTZ    NOT NULL,
    CONSTRAINT fk_product_sales_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_product_sales_units_non_negative CHECK (units_sold >= 0)
);
