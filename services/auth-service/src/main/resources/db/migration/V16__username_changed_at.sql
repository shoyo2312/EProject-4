-- When the account last chose a new handle, for the once-every-30-days rule on
-- PUT /api/v1/auth/me/username. Null until the first change: the handle picked at signup, or
-- invented for a social one, does not count against the allowance.
ALTER TABLE users ADD COLUMN username_changed_at TIMESTAMPTZ;
