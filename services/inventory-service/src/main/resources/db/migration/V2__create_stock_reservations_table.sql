CREATE TABLE stock_reservations (
    id              BIGINT PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    product_id      BIGINT       NOT NULL,
    quantity        INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_stock_reservations_order ON stock_reservations (order_id, status);
