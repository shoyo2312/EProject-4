CREATE TABLE verification_tokens (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users (id),
    token_hash VARCHAR(64) NOT NULL,
    token_type VARCHAR(30) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_verification_tokens_user_type ON verification_tokens (user_id, token_type);
CREATE UNIQUE INDEX uq_verification_tokens_hash ON verification_tokens (token_hash);
