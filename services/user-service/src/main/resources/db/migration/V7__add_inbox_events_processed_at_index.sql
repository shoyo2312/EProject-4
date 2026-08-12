-- Retention deletes the oldest rows first (ORDER BY processed_at LIMIT n). Without this index
-- that ordering is a sequential scan plus a sort over the whole table, on exactly the table the
-- job exists because it has grown too large. The unique index on event_id serves the claim path
-- and says nothing about age, so it cannot help here.
CREATE INDEX idx_inbox_events_processed_at ON inbox_events (processed_at);
