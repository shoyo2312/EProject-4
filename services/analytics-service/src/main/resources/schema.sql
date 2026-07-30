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
