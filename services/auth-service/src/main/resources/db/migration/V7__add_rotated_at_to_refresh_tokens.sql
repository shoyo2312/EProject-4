-- Separates "retired because /refresh issued a successor" from "revoked by logout / password
-- reset". Only the former can be replayed by an attacker holding a stolen copy of the chain,
-- so only the former should trigger family-wide revocation. Existing rows stay NULL: their
-- history is unknown, and treating them as rotated would revoke live sessions on first refresh.
ALTER TABLE refresh_tokens
    ADD COLUMN rotated_at TIMESTAMPTZ;
