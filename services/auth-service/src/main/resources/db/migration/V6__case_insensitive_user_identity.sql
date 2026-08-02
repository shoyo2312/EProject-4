-- Email and username were matched byte-for-byte, so Test@Gmail.com and test@gmail.com were two
-- distinct accounts and a user who typed the "wrong" casing at login got INVALID_CREDENTIALS.
--
-- Fails loudly if the table already holds rows that collide once case is ignored — those have to
-- be merged or soft-deleted by hand before this migration can run.
DO $$
DECLARE
    conflict text;
BEGIN
    SELECT lower(email) INTO conflict
    FROM users WHERE deleted_at IS NULL
    GROUP BY lower(email) HAVING count(*) > 1 LIMIT 1;
    IF conflict IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate accounts differing only in email case: %', conflict;
    END IF;

    SELECT lower(username) INTO conflict
    FROM users WHERE deleted_at IS NULL
    GROUP BY lower(username) HAVING count(*) > 1 LIMIT 1;
    IF conflict IS NOT NULL THEN
        RAISE EXCEPTION 'Duplicate accounts differing only in username case: %', conflict;
    END IF;
END $$;

UPDATE users SET email = lower(email) WHERE email <> lower(email);

DROP INDEX uq_users_email;
DROP INDEX uq_users_username;

-- Functional indexes: the ...IgnoreCase repository methods emit lower(col) = lower(?), which
-- matches these, so lookups stay index-backed.
CREATE UNIQUE INDEX uq_users_email ON users (lower(email)) WHERE deleted_at IS NULL;
CREATE UNIQUE INDEX uq_users_username ON users (lower(username)) WHERE deleted_at IS NULL;
