-- ReplacingMergeTree(event_id) makes re-processing the same Kafka event idempotent: duplicate
-- rows are deduped by ClickHouse's background merges. Reads that need an exact count (not an
-- eventually-consistent one) must query with the FINAL modifier.

CREATE TABLE IF NOT EXISTS engagement_events (
    event_id    String,
    event_type  LowCardinality(String), -- PUBLISHED, LIKED, UNLIKED, COMMENTED, SHARED
    video_id    String,
    user_id     Int64,
    occurred_at DateTime64(3)
) ENGINE = ReplacingMergeTree
ORDER BY event_id;

CREATE TABLE IF NOT EXISTS revenue_events (
    event_id    String,
    event_type  LowCardinality(String), -- ORDER_CREATED, ORDER_CONFIRMED, ORDER_CANCELLED, PAYMENT_COMPLETED, PAYMENT_FAILED
    order_id    Int64,
    user_id     Nullable(Int64),
    amount      Nullable(Decimal(12, 2)),
    occurred_at DateTime64(3)
) ENGINE = ReplacingMergeTree
ORDER BY event_id;

CREATE TABLE IF NOT EXISTS user_signup_events (
    event_id    String,
    user_id     Int64,
    username    String,
    occurred_at DateTime64(3)
) ENGINE = ReplacingMergeTree
ORDER BY event_id;

-- Training data for the ranking model. Ordered by time first, not by event_id, because every
-- query the trainer runs is a time slice ("features from before the cutoff, labels from after")
-- and event_id order would make each of those a full scan. Deduplication still holds: a replayed
-- event carries the same occurred_at as the original, so the sort key is stable per event.
CREATE TABLE IF NOT EXISTS watch_events (
    event_id    String,
    video_id    String,
    user_id     Int64,
    watched_ms  Int64,
    duration_ms Int64,
    completed   UInt8,
    occurred_at DateTime64(3)
) ENGINE = ReplacingMergeTree
ORDER BY (occurred_at, event_id);

-- One row per (video, tag). Tags are the only content feature the ranker has, and they arrive
-- on the publish event because no service outside video-service can read a video document.
CREATE TABLE IF NOT EXISTS video_tags (
    video_id     String,
    tag          String,
    published_at DateTime64(3)
) ENGINE = ReplacingMergeTree
ORDER BY (video_id, tag);
