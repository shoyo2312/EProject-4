-- When the owner last changed their display name, for the once-every-7-days rule on
-- PATCH /api/v1/users/me. Null until the first change: the name a profile starts with is copied
-- from the handle at registration, which is not the owner's choice and costs them nothing.
ALTER TABLE user_profiles ADD COLUMN display_name_changed_at TIMESTAMPTZ;
