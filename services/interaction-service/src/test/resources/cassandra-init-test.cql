-- interaction-service keyspace + tables (likes, comments, shares, counters)

CREATE KEYSPACE IF NOT EXISTS interaction_ks
  WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

USE interaction_ks;

-- "Did user X like video Y" membership + idempotent like/unlike via LWT (IF NOT EXISTS / IF EXISTS)
CREATE TABLE IF NOT EXISTS likes_by_video (
    video_id   bigint,
    user_id    bigint,
    created_at timestamp,
    PRIMARY KEY ((video_id), user_id)
);

-- Reverse index: videos a user liked
CREATE TABLE IF NOT EXISTS likes_by_user (
    user_id    bigint,
    video_id   bigint,
    created_at timestamp,
    PRIMARY KEY ((user_id), video_id)
) WITH CLUSTERING ORDER BY (video_id DESC);

-- Videos a user saved ("favorites"). One table, not the like pair: nothing needs the
-- "who saved video X" direction, and (user_id, video_id) already answers both "is it saved"
-- and "list my saves". Save/unsave stay idempotent via LWT on this table.
CREATE TABLE IF NOT EXISTS saves_by_user (
    user_id    bigint,
    video_id   bigint,
    created_at timestamp,
    PRIMARY KEY ((user_id), video_id)
) WITH CLUSTERING ORDER BY (video_id DESC);

-- Comments, partitioned by video, clustered by Snowflake id (time-ordered) descending
CREATE TABLE IF NOT EXISTS comments_by_video (
    video_id   bigint,
    comment_id bigint,
    user_id    bigint,
    content    text,
    created_at timestamp,
    deleted_at timestamp,
    PRIMARY KEY ((video_id), comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);

-- Shares, same shape as comments minus content
CREATE TABLE IF NOT EXISTS shares_by_video (
    video_id   bigint,
    share_id   bigint,
    user_id    bigint,
    created_at timestamp,
    PRIMARY KEY ((video_id), share_id)
) WITH CLUSTERING ORDER BY (share_id DESC);

-- Counter columns must live in their own table (Cassandra restriction)
CREATE TABLE IF NOT EXISTS video_counters (
    video_id      bigint PRIMARY KEY,
    like_count    counter,
    comment_count counter,
    share_count   counter,
    view_count    counter
);

-- For clusters created before view_count existed: CREATE TABLE IF NOT EXISTS above is a no-op
-- on an existing table and would leave the column missing.
ALTER TABLE video_counters ADD IF NOT EXISTS view_count counter;
