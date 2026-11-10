-- The Monday of the last week the weekly screen time update was sent for, or claimed and skipped
-- because nothing was recorded. Written only by WeeklyScreenTimeDigest's conditional update, which
-- is also its claim: two instances running the job cannot both send the same week, and the entity
-- maps it read-only so a settings save cannot write an older value back. See docs/user-settings.md.
ALTER TABLE user_settings ADD COLUMN last_digest_week DATE;
