-- Devices an ADMIN chose to trust, so the email-OTP step of admin login is skipped for 30 days.
-- Infrastructure table like refresh_tokens / verification_tokens: no BaseEntity, no soft delete,
-- pruned by ExpiredRecordCleanup once expired. The token itself lives only in an httpOnly cookie
-- on the admin console; only its SHA-256 is stored here.
CREATE TABLE remembered_devices (
    id         BIGINT PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id),
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX uq_remembered_devices_hash ON remembered_devices (token_hash);
CREATE INDEX idx_remembered_devices_user ON remembered_devices (user_id);
