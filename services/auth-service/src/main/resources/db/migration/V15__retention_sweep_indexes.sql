-- What ExpiredRecordCleanup asks of these tables: the oldest rows past a cutoff, a batch at a time
-- (WHERE expires_at < ? ORDER BY expires_at LIMIT n, and the same on published_at for the outbox).
-- None of those columns was indexed, so every batch was a sequential scan plus a sort of the whole
-- table, repeated up to max-batches-per-run times — on exactly the tables the job exists for
-- because they grow without bound. Same fix user-service made in V7 for inbox_events, and the
-- outbox index is the one user-service and admin-service already have under this name.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

CREATE INDEX idx_verification_tokens_expires_at ON verification_tokens (expires_at);

CREATE INDEX idx_outbox_events_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
