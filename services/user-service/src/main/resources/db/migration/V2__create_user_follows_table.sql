CREATE TABLE user_follows (
    id              BIGINT PRIMARY KEY,
    follower_id     BIGINT       NOT NULL,
    following_id    BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_follows_follower_following ON user_follows (follower_id, following_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_follows_follower_id ON user_follows (follower_id) WHERE deleted_at IS NULL;
CREATE INDEX idx_user_follows_following_id ON user_follows (following_id) WHERE deleted_at IS NULL;
