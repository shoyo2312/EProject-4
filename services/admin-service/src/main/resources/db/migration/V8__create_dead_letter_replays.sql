-- Every DLT record an admin replayed — or tried to — from the console's Queues & DLQ screen, one row
-- per record handled. Kafka keeps no trace of a replay that did not happen: a record held back by a
-- later event under its key looks the same on the DLT as one nobody has looked at. This is what the
-- console shows next to each record, and who chose to force one past the order check.
CREATE TABLE dead_letter_replays (
    id             BIGINT        PRIMARY KEY,
    admin_id       BIGINT        NOT NULL,
    dlt_topic      VARCHAR(255)  NOT NULL,
    dlt_partition  INT           NOT NULL,
    dlt_offset     BIGINT        NOT NULL,
    -- The DLT record's own timestamp. A topic deleted and created again starts its offsets over,
    -- and without this a new record at an old offset would show the old one's replay.
    parked_at      TIMESTAMPTZ   NOT NULL,
    outcome        VARCHAR(20)   NOT NULL,
    detail         VARCHAR(500),
    forced         BOOLEAN       NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    deleted_at     TIMESTAMPTZ,
    version        BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_dead_letter_replays_record ON dead_letter_replays (dlt_topic, dlt_offset);
