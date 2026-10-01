CREATE TABLE products (
    id          BIGSERIAL      PRIMARY KEY,
    sku         VARCHAR(64)    NOT NULL,
    name        VARCHAR(255)   NOT NULL,
    description TEXT,
    price       NUMERIC(12, 2) NOT NULL,
    created_at  TIMESTAMPTZ    NOT NULL,
    updated_at  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_price_non_negative CHECK (price >= 0)
);
