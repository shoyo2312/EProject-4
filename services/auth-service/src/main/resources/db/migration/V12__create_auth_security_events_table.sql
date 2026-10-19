-- The account security log: sign-ins and failed attempts, password resets, address changes, and
-- the admin second factor. Until now none of this was recorded anywhere — users.last_login_at is
-- a single timestamp that every refresh overwrites, so every sign-in but the most recent was lost
-- the moment it happened, and a failed one was never written down at all.
--
-- Infrastructure table like refresh_tokens and remembered_devices: append-only, no BaseEntity, no
-- soft delete, pruned by ExpiredRecordCleanup past auth.retention.security-event-grace. A log's
-- whole problem is how many rows it has, so marking them deleted would solve nothing.
--
-- Nothing secret is stored. ip_address and user_agent are what the request arrived with, and
-- detail is a short phrase that never carries a password, a token or an OTP.
--
-- No foreign key to users, deliberately. An entry is written in its own transaction, so that a
-- failed sign-in is still recorded when the attempt it describes rolls back; a foreign key would
-- make that write depend on the account row being visible to a *different* transaction, and the
-- entry would be silently dropped whenever the caller had just created or was still holding the
-- account. A log must never fail, or quietly not happen, because of the state of the table it
-- describes — and pointing the other way it would make this log a reason the users table cannot
-- be changed. Orphans are not a concern: accounts here are soft-deleted, and retention prunes
-- this table by age.
CREATE TABLE auth_security_events (
    id           BIGINT      PRIMARY KEY,
    user_id      BIGINT      NOT NULL,
    event_type   VARCHAR(32) NOT NULL,
    -- VARCHAR rather than INET: 45 characters is the longest IPv6 form, and INET would buy
    -- containment queries nobody asks for at the cost of a custom JPA type.
    ip_address   VARCHAR(45),
    user_agent   VARCHAR(512),
    device_label VARCHAR(128),
    detail       VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL
);

-- The console reads one account newest-first and never any other way, so the index carries the
-- order too and the page is a range scan rather than a sort of everything that account ever did.
CREATE INDEX idx_auth_security_events_user ON auth_security_events (user_id, created_at DESC);

-- What the retention sweep asks for, and nothing else reads this way.
CREATE INDEX idx_auth_security_events_created ON auth_security_events (created_at);
