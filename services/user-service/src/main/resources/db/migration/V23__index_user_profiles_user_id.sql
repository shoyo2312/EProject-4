-- A plain index on user_id, beside V1's partial unique one. Postgres uses a partial index only
-- when the query's WHERE implies its predicate, so uq_user_profiles_user_id serves nothing that
-- does not also say deleted_at IS NULL: the follow counter updates, the closed-account checks and
-- UserFollowRepository's deactivated-profile subqueries all filter on user_id alone, and every one
-- of them was a sequential scan. The unique index stays: it is what allows one live profile per
-- user.
CREATE INDEX idx_user_profiles_user_id ON user_profiles (user_id);
