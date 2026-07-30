CREATE TABLE inventory_items (
    id                  BIGINT PRIMARY KEY,
    product_id          BIGINT      NOT NULL,
    seller_id           BIGINT      NOT NULL,
    available_quantity  INT         NOT NULL DEFAULT 0,
    reserved_quantity    INT         NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    deleted_at          TIMESTAMPTZ,
    version             BIGINT      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_inventory_items_product ON inventory_items (product_id) WHERE deleted_at IS NULL;
