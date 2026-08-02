CREATE TABLE user_mutes (
    id              BIGINT PRIMARY KEY,
    muter_id        BIGINT       NOT NULL,
    muted_id        BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_mutes_muter_muted ON user_mutes (muter_id, muted_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_mutes_muter_id ON user_mutes (muter_id) WHERE deleted_at IS NULL;
