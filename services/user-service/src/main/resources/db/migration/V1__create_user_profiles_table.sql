CREATE TABLE user_profiles (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    bio             VARCHAR(500),
    avatar_url      VARCHAR(500),
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    deleted_at      TIMESTAMPTZ,
    version         BIGINT       NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_user_profiles_user_id ON user_profiles (user_id) WHERE deleted_at IS NULL;
