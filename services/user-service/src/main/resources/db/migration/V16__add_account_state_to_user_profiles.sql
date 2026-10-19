-- The owner closed the account (deactivated it, or asked for it to be deleted). A deactivated
-- profile is not there for anyone else: reads answer 404, and lists, search and the batch leave it
-- out. Its follow edges stay, and with them every counter, so reopening the account brings it back
-- exactly as it was.
--
-- account_state_changed_at is the occurredAt of the last AccountStateChangedEvent applied: a late
-- re-send of an older one must not undo a newer change. Both NULL for every existing profile,
-- which reads as open.
ALTER TABLE user_profiles ADD COLUMN deactivated_at TIMESTAMPTZ;
ALTER TABLE user_profiles ADD COLUMN account_state_changed_at TIMESTAMPTZ;
