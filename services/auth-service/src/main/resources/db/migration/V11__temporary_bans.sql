-- When a ban lapses. NULL means it does not, which is what every ban before this column was.
--
-- Partial index on exactly what the sweep asks for — the banned rows with a deadline — so the
-- job that runs every few minutes never walks the user table.
ALTER TABLE users ADD COLUMN banned_until TIMESTAMPTZ;

CREATE INDEX idx_users_banned_until ON users (banned_until)
    WHERE status = 'BANNED' AND banned_until IS NOT NULL;
