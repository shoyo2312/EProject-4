CREATE TABLE cart_items (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    product_id      BIGINT        NOT NULL,
    product_name    VARCHAR(200)  NOT NULL,
    price           NUMERIC(12,2) NOT NULL,
    quantity        INT           NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_cart_items_user_product ON cart_items (user_id, product_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_cart_items_user ON cart_items (user_id) WHERE deleted_at IS NULL;
