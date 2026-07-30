-- Immutable ledger: rows are inserted once and never updated or deleted -- no version /
-- deleted_at / updated_at columns, unlike the soft-delete + optimistic-lock entities
-- elsewhere in this monorepo.
CREATE TABLE payment_transactions (
    id              BIGINT        PRIMARY KEY,
    order_id        BIGINT        NOT NULL,
    user_id         BIGINT        NOT NULL,
    amount          NUMERIC(12,2) NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    failure_reason  VARCHAR(500),
    created_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_payment_transactions_order ON payment_transactions (order_id);
CREATE INDEX idx_payment_transactions_user ON payment_transactions (user_id);
