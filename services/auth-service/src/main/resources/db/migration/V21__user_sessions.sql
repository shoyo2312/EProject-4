-- One row per signed-in device: what the account owner sees under "Devices" and can sign out
-- remotely. A session outlives its refresh tokens — every /refresh retires one row of
-- refresh_tokens and writes another — so the tokens point at it through session_id and it is the
-- session, not any single token, that is listed, limited and revoked.
--
-- Infrastructure table like refresh_tokens: no BaseEntity, hard-deleted by ExpiredRecordCleanup
-- once expired or revoked past auth.retention.session-grace, and on account erasure.
--
-- No foreign key to users, for the same reason as auth_security_events: tests and erasure delete
-- users freely, and a stale session row is harmless — it is revoked or expired, so never listed.
CREATE TABLE user_sessions (
    id              BIGINT       PRIMARY KEY,
    user_id         BIGINT       NOT NULL,
    -- DESKTOP or MOBILE. The device limit is one active session per type (SessionRegistry).
    device_type     VARCHAR(16)  NOT NULL,
    device_label    VARCHAR(128),
    device_model    VARCHAR(64),
    ip_address      VARCHAR(45),
    location        VARCHAR(128),
    created_at      TIMESTAMPTZ  NOT NULL,
    last_active_at  TIMESTAMPTZ  NOT NULL,
    -- The expiry of the session's newest refresh token: past it the device can no longer refresh,
    -- so the session is over without anybody revoking it.
    expires_at      TIMESTAMPTZ  NOT NULL,
    revoked_at      TIMESTAMPTZ,
    -- LOGOUT, REMOTE_LOGOUT, REPLACED (device limit), ALL (reset, ban, replay, closure).
    revoke_reason   VARCHAR(32)
);

-- The list, and the device-limit check, read only live sessions of one account.
CREATE INDEX idx_user_sessions_user_active ON user_sessions (user_id) WHERE revoked_at IS NULL;

-- What the retention sweep asks for.
CREATE INDEX idx_user_sessions_expires ON user_sessions (expires_at);

-- Null on tokens issued before this migration; such a token gets a session the first time it is
-- refreshed (SessionRegistry.adopt).
ALTER TABLE refresh_tokens ADD COLUMN session_id BIGINT;
CREATE INDEX idx_refresh_tokens_session ON refresh_tokens (session_id) WHERE revoked_at IS NULL;
