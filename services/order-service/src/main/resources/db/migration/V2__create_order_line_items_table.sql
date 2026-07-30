CREATE TABLE order_line_items (
    id              BIGINT        PRIMARY KEY,
    order_id        BIGINT        NOT NULL,
    product_id      BIGINT        NOT NULL,
    product_name    VARCHAR(200)  NOT NULL,
    quantity        INT           NOT NULL,
    price           NUMERIC(12,2) NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_order_line_items_order ON order_line_items (order_id);
