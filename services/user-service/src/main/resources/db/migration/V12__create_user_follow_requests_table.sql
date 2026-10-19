-- A follow of a private account, waiting for its owner. Same shape as user_follows (V2): Snowflake
-- id, audit columns, soft delete. Approving, rejecting, withdrawing and blocking all end a request
-- by setting deleted_at, and the unique index covers live rows only, so asking again after a
-- rejection is a new request rather than a conflict.
CREATE TABLE user_follow_requests (
    id            BIGINT       PRIMARY KEY,
    requester_id  BIGINT       NOT NULL,
    target_id     BIGINT       NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL,
    deleted_at    TIMESTAMPTZ,
    version       BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_follow_requests_requester_target
    ON user_follow_requests (requester_id, target_id) WHERE deleted_at IS NULL;
-- The owner's requests list, newest first.
CREATE INDEX idx_user_follow_requests_target_created
    ON user_follow_requests (target_id, created_at DESC) WHERE deleted_at IS NULL;
