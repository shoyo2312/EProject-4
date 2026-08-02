CREATE TABLE user_blocks (
    id              BIGINT PRIMARY KEY,
    blocker_id      BIGINT       NOT NULL,
    blocked_id      BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_blocks_blocker_blocked ON user_blocks (blocker_id, blocked_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_blocks_blocker_id ON user_blocks (blocker_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_blocks_blocked_id ON user_blocks (blocked_id) WHERE deleted_at IS NULL;
