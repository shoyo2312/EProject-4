-- When a ban lapses. NULL is a permanent ban, which is what every row written before this
-- column existed was, so no backfill is needed.
--
-- The audit log keeps it as well as the event that carries it downstream: auth-service holds
-- the ban that is currently in force, this holds what was decided and by whom, and a lapsed
-- ban leaves the second one standing after the first has been cleared.
ALTER TABLE moderation_actions ADD COLUMN banned_until TIMESTAMPTZ;
