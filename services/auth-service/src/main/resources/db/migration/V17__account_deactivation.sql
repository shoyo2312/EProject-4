-- The owner closing their own account. status = 'DEACTIVATED' is what every sign-in path reads;
-- deactivated_at is when, and outlives a ban laid on top so that lifting the ban returns the
-- account to DEACTIVATED rather than ACTIVE. deletion_scheduled_at is set when the owner asked for
-- deletion rather than a pause, and cleared if they reopen the account first.
--
-- Partial index on exactly what AccountDeletionSweep asks for, so the hourly job never walks the
-- user table.
ALTER TABLE users ADD COLUMN deactivated_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN deletion_scheduled_at TIMESTAMPTZ;

CREATE INDEX idx_users_deletion_scheduled_at ON users (deletion_scheduled_at)
    WHERE deleted_at IS NULL AND deletion_scheduled_at IS NOT NULL;
