CREATE TABLE inbox_events (
    id              BIGINT PRIMARY KEY,
    event_id        VARCHAR(100) NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL
);

CREATE UNIQUE INDEX uq_inbox_events_event_id ON inbox_events (event_id);
