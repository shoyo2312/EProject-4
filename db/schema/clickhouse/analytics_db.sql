CREATE TABLE analytics_db.engagement_events
(
    `event_id` String,
    `event_type` LowCardinality(String),
    `video_id` String,
    `user_id` Int64,
    `occurred_at` DateTime64(3)
)
ENGINE = ReplacingMergeTree
ORDER BY (video_id, event_id)
SETTINGS index_granularity = 8192
;

CREATE TABLE analytics_db.user_signup_events
(
    `event_id` String,
    `user_id` Int64,
    `username` String,
    `occurred_at` DateTime64(3)
)
ENGINE = ReplacingMergeTree
ORDER BY event_id
SETTINGS index_granularity = 8192
;

CREATE TABLE analytics_db.video_tags
(
    `video_id` String,
    `tag` String,
    `published_at` DateTime64(3)
)
ENGINE = ReplacingMergeTree
ORDER BY (video_id, tag)
SETTINGS index_granularity = 8192
;

CREATE TABLE analytics_db.watch_events
(
    `event_id` String,
    `video_id` String,
    `user_id` Int64,
    `watched_ms` Int64,
    `duration_ms` Int64,
    `completed` UInt8,
    `occurred_at` DateTime64(3)
)
ENGINE = ReplacingMergeTree
ORDER BY (occurred_at, event_id)
SETTINGS index_granularity = 8192
;


ALTER TABLE analytics_db.watch_events ADD COLUMN IF NOT EXISTS source LowCardinality(String) DEFAULT 'OTHER';
ALTER TABLE analytics_db.watch_events ADD COLUMN IF NOT EXISTS search_query String DEFAULT '';
