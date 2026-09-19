-- Supports the retention sweep's "published before X, oldest first" scan. The existing index is
-- partial on published_at IS NULL and covers the publisher's query, which is the opposite half
-- of the table.
CREATE INDEX idx_outbox_events_published_at ON outbox_events (published_at)
    WHERE published_at IS NOT NULL;
