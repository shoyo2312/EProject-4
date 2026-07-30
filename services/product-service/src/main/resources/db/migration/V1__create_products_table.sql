CREATE TABLE products (
    id              BIGINT PRIMARY KEY,
    seller_id       BIGINT        NOT NULL,
    name            VARCHAR(200)  NOT NULL,
    description     VARCHAR(2000),
    price           NUMERIC(12,2) NOT NULL,
    category        VARCHAR(100),
    image_url       VARCHAR(500),
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ   NOT NULL,
    updated_at      TIMESTAMPTZ   NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_products_seller ON products (seller_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_products_status ON products (status) WHERE deleted_at IS NULL;
