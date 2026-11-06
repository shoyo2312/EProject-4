-- Every refresh token descended from one sign-in shares a family id, so a replayed token can end
-- its own chain instead of every session the user has. Existing rows stay NULL: which chain they
-- belong to is unknown, and their replay keeps the old account-wide answer until they expire.
ALTER TABLE refresh_tokens
    ADD COLUMN family_id BIGINT;

CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
