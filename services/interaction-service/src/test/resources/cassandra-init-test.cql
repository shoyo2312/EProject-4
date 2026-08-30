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
    created_at timestamp,
    video_id   bigint,
    PRIMARY KEY ((user_id), created_at, video_id)
) WITH CLUSTERING ORDER BY (created_at DESC, video_id ASC);

-- The claim on a save: one row per (user, video), which is what "is this video saved" reads
-- and what the LWT in SaveServiceImpl takes before writing the listing row. Not the like pair:
-- nothing needs the "who saved video X" direction.
CREATE TABLE IF NOT EXISTS saves_by_user (
    user_id    bigint,
    video_id   bigint,
    created_at timestamp,
    PRIMARY KEY ((user_id), video_id)
) WITH CLUSTERING ORDER BY (video_id DESC);

-- Videos a user saved ("favorites"), by save time. Ordering is the whole reason this table is
-- separate from saves_by_user: video_id is a Snowflake, so clustering on it orders by when the
-- video was made, not by when the viewer saved it — saving an old video would file it at the
-- bottom of that viewer's favourites.
CREATE TABLE IF NOT EXISTS saves_by_user_time (
    user_id    bigint,
    created_at timestamp,
    video_id   bigint,
    PRIMARY KEY ((user_id), created_at, video_id)
) WITH CLUSTERING ORDER BY (created_at DESC, video_id ASC);

-- Comments, partitioned by video, clustered by Snowflake id (time-ordered) descending.
-- parent_id: null for a top-level comment, the top-level comment_id for a reply.
CREATE TABLE IF NOT EXISTS comments_by_video (
    video_id   bigint,
    comment_id bigint,
    user_id    bigint,
    content    text,
    created_at timestamp,
    deleted_at timestamp,
    parent_id  bigint,
    reply_to_user_id bigint,
    likes      int,
    PRIMARY KEY ((video_id), comment_id)
) WITH CLUSTERING ORDER BY (comment_id DESC);

-- "Did user X like comment Y" membership; per-comment count denormalised onto comments_by_video.likes.
CREATE TABLE IF NOT EXISTS comment_likes (
    comment_id bigint,
    user_id    bigint,
    created_at timestamp,
    PRIMARY KEY ((comment_id), user_id)
);

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
