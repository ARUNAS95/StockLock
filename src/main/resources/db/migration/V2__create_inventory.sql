-- One inventory row per product. All mutations are single conditional UPDATE statements
-- (see InventoryRepository); the CHECK constraints are the last line of defence if a code
-- path ever forgets the WHERE guard: the statement fails instead of overselling.
CREATE TABLE inventory (
    id                 BIGSERIAL   PRIMARY KEY,
    product_id         BIGINT      NOT NULL,
    available_quantity INTEGER     NOT NULL,
    reserved_quantity  INTEGER     NOT NULL DEFAULT 0,
    version            BIGINT      NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_inventory_product UNIQUE (product_id),
    CONSTRAINT fk_inventory_product FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT ck_inventory_available_non_negative CHECK (available_quantity >= 0),
    CONSTRAINT ck_inventory_reserved_non_negative CHECK (reserved_quantity >= 0)
);
