-- Social login (Google, Facebook). An account can now exist without either a password or an
-- email, so both columns lose NOT NULL.
--
-- password_hash: a user who only ever signed in with Google has no password to store. Login by
-- password must therefore treat a null hash as "no password set" rather than dereference it.
--
-- email: Facebook's `email` permission is optional and an account registered with a phone number
-- has no address to give, so /me returns no email at all. Rejecting those logins would mean
-- supporting Facebook for only some of its users, so the account is created without one and the
-- client is told to ask for it later.
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;
ALTER TABLE users ALTER COLUMN email         DROP NOT NULL;

-- uq_users_email is left alone on purpose: it is a partial unique index on lower(email), and
-- Postgres treats every NULL as distinct, so any number of email-less accounts coexist under it
-- while two accounts still cannot share an address.

-- One row per (account, provider), so a single account can carry both a Google and a Facebook
-- identity. Infrastructure table, not a business entity: no BaseEntity, no soft delete.
CREATE TABLE user_identities (
    id           BIGINT       PRIMARY KEY,
    user_id      BIGINT       NOT NULL REFERENCES users (id),
    provider     VARCHAR(20)  NOT NULL,
    provider_uid VARCHAR(128) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL
);

-- The uniqueness is the concurrency control, not merely a data rule: two simultaneous first-time
-- logins with the same provider account both find nothing and both go on to create a user, and
-- only this index stops the second one from succeeding.
CREATE UNIQUE INDEX uq_identity ON user_identities (provider, provider_uid);

CREATE INDEX idx_identities_user ON user_identities (user_id);
