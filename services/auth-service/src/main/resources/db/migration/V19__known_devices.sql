-- Devices an account has signed in or refreshed a session from, by the client's X-Device-Id;
-- only its SHA-256 is kept. Infrastructure table like remembered_devices: no BaseEntity,
-- hard-deleted. At most 50 per account (KnownDevices), forgotten 180 days after last use
-- (ExpiredRecordCleanup), removed when the account is erased.
CREATE TABLE known_devices (
    id           BIGINT PRIMARY KEY,
    user_id      BIGINT      NOT NULL REFERENCES users (id),
    device_hash  VARCHAR(64) NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_known_devices_user_device UNIQUE (user_id, device_hash)
);

CREATE INDEX idx_known_devices_last_seen ON known_devices (last_seen_at);

-- One row per account that has ever had a known device. Never swept: it is what tells an
-- account's first device from a returning account whose devices all aged out, which must
-- still alert.
CREATE TABLE known_device_accounts (
    user_id BIGINT PRIMARY KEY REFERENCES users (id)
);
