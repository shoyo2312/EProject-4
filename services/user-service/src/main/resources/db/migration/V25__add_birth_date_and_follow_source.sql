-- Birth date: collected at sign-up since the start but never stored. Nullable — social sign-ins
-- and accounts older than this column are asked for it once after sign-in. Set once, never edited.
ALTER TABLE user_profiles ADD COLUMN birth_date DATE;

-- The video a follow request came from, kept until the private account's owner approves it, so
-- the approved follow is credited to that video in Studio like a direct follow would be.
ALTER TABLE user_follow_requests ADD COLUMN source_video_id BIGINT;
