CREATE TABLE orders (
    id              BIGINT        PRIMARY KEY,
    user_id         BIGINT        NOT NULL,
    total_amount    NUMERIC(12,2) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    cancel_reason   VARCHAR(500),
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_orders_user ON orders (user_id) WHERE deleted_at IS NULL;
