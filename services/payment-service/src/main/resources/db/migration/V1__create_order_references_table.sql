-- Lightweight lookup populated from OrderCreatedEvent, read back when InventoryReservedEvent
-- arrives to charge the right user -- InventoryReservedEvent itself carries no userId.
CREATE TABLE order_references (
    id              BIGINT PRIMARY KEY,
    order_id        BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_order_references_order_id ON order_references (order_id);
