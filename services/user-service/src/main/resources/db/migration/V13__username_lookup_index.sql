-- The handle lookup behind GET /users/by-username/{handle} (UserProfileRepository
-- .findFirstByUsernameIgnoreCaseAndDeletedAtIsNull). V8's trigram indexes serve the substring
-- search, not equality, so every profile opened by its handle was a sequential scan.
CREATE INDEX idx_user_profiles_username_lower ON user_profiles (lower(username)) WHERE deleted_at IS NULL;
