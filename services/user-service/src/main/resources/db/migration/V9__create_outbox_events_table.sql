-- Follow events used to go straight to Kafka from inside the follow transaction, blocking on the
-- broker ack. This table takes their place: the row commits with the follow edge, and
-- OutboxPublisher drains it afterwards.
CREATE TABLE outbox_events (
    id              BIGINT PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    payload         TEXT         NOT NULL,
    published_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL
);

-- The publisher's poll: unpublished rows, oldest first.
CREATE INDEX idx_outbox_events_unpublished ON outbox_events (created_at) WHERE published_at IS NULL;

-- The retention sweep's opposite half: published before X, oldest first.
CREATE INDEX idx_outbox_events_published_at ON outbox_events (published_at) WHERE published_at IS NOT NULL;
