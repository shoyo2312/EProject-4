package com.tiktok.analyticsservice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * TikTok Studio's numbers: the same streams the admin console reads, narrowed to one creator.
 *
 * <p>Ownership comes from the PUBLISHED row {@code VideoEventConsumer} writes for every upload, whose
 * {@code user_id} is the uploader — so this service needs no copy of video-service's documents and no
 * call to it per request. A creator only ever sees rows whose video that row says is theirs.
 *
 * <p>Days are counted in Vietnam's zone, not the server's: a creator reading "today" means their own
 * midnight, and a server in UTC would move every evening's views into the next day. The day comes back
 * as text so that no JDBC date conversion can shift it again on the way out.
 *
 * <p>Every read over the event tables uses FINAL, for the reason given on {@link WatchEventRepository}.
 */
@Repository
@RequiredArgsConstructor
public class CreatorAnalyticsRepository {

    public static final String ZONE = "Asia/Ho_Chi_Minh";

    /**
     * ponytail: scans every PUBLISHED row (engagement_events is ordered by video, not by owner). Fine
     * at this platform's size; a video_owners table ordered by owner is the upgrade when it is not.
     */
    private static final String OWNED_BY =
            "video_id IN (SELECT video_id FROM engagement_events WHERE event_type = 'PUBLISHED' AND user_id = ?)";

    private static final String IN_WINDOW =
            "occurred_at >= fromUnixTimestamp64Milli(?) AND occurred_at < fromUnixTimestamp64Milli(?)";

    private final JdbcTemplate jdbcTemplate;

    /** Which videos a query covers: everything one creator uploaded, or a single video. */
    public record Scope(String filter, Object param) {

        public static Scope ownedBy(long ownerId) {
            return new Scope(OWNED_BY, ownerId);
        }

        public static Scope video(String videoId) {
            return new Scope("video_id = ?", videoId);
        }
    }

    public record DailyViews(LocalDate day, long views) {
    }

    public record DailyEngagement(LocalDate day, long likes, long comments, long shares) {
    }

    public record DailyFollows(LocalDate day, long gained, long lost) {
    }

    public record WatchTotals(String videoId, long views, long viewers, long watchedMs, long completions) {
    }

    public record EngagementTotals(String videoId, long likes, long comments, long shares) {
    }

    public void insertFollow(String eventId, long followerId, long followingId, boolean followed, Instant occurredAt) {
        jdbcTemplate.update(
                "INSERT INTO follow_events (event_id, follower_id, following_id, followed, occurred_at) VALUES (?, ?, ?, ?, ?)",
                eventId, followerId, followingId, followed ? 1 : 0, occurredAt);
    }

    public boolean ownsVideo(long ownerId, String videoId) {
        Long rows = jdbcTemplate.queryForObject(
                "SELECT count() FROM engagement_events WHERE video_id = ? AND event_type = 'PUBLISHED' AND user_id = ?",
                Long.class, videoId, ownerId);
        return rows != null && rows > 0;
    }

    public List<DailyViews> dailyViews(Scope scope, Instant from, Instant to) {
        return jdbcTemplate.query(
                """
                SELECT toString(toDate(occurred_at, '%s')) AS day, count() AS views
                FROM watch_events FINAL
                WHERE %s AND %s
                GROUP BY day
                """.formatted(ZONE, IN_WINDOW, scope.filter()),
                (rs, i) -> new DailyViews(LocalDate.parse(rs.getString("day")), rs.getLong("views")),
                from.toEpochMilli(), to.toEpochMilli(), scope.param());
    }

    public List<DailyEngagement> dailyEngagement(Scope scope, Instant from, Instant to) {
        return jdbcTemplate.query(
                """
                SELECT toString(toDate(occurred_at, '%s')) AS day,
                       countIf(event_type = 'LIKED') - countIf(event_type = 'UNLIKED') AS likes,
                       countIf(event_type = 'COMMENTED') - countIf(event_type = 'UNCOMMENTED') AS comments,
                       countIf(event_type = 'SHARED') AS shares
                FROM engagement_events FINAL
                WHERE %s AND %s
                GROUP BY day
                """.formatted(ZONE, IN_WINDOW, scope.filter()),
                (rs, i) -> new DailyEngagement(LocalDate.parse(rs.getString("day")),
                        rs.getLong("likes"), rs.getLong("comments"), rs.getLong("shares")),
                from.toEpochMilli(), to.toEpochMilli(), scope.param());
    }

    public List<DailyFollows> dailyFollows(long ownerId, Instant from, Instant to) {
        return jdbcTemplate.query(
                """
                SELECT toString(toDate(occurred_at, '%s')) AS day,
                       countIf(followed = 1) AS gained,
                       countIf(followed = 0) AS lost
                FROM follow_events FINAL
                WHERE following_id = ? AND %s
                GROUP BY day
                """.formatted(ZONE, IN_WINDOW),
                (rs, i) -> new DailyFollows(LocalDate.parse(rs.getString("day")),
                        rs.getLong("gained"), rs.getLong("lost")),
                ownerId, from.toEpochMilli(), to.toEpochMilli());
    }

    /** One row per video watched in the window. {@code user_id > 0} keeps anonymous playback out of viewers. */
    public List<WatchTotals> watchTotals(Scope scope, Instant from, Instant to) {
        return jdbcTemplate.query(
                """
                SELECT video_id,
                       count() AS views,
                       uniqExactIf(user_id, user_id > 0) AS viewers,
                       sum(watched_ms) AS watched_ms,
                       countIf(completed = 1) AS completions
                FROM watch_events FINAL
                WHERE %s AND %s
                GROUP BY video_id
                """.formatted(IN_WINDOW, scope.filter()),
                (rs, i) -> new WatchTotals(rs.getString("video_id"), rs.getLong("views"), rs.getLong("viewers"),
                        rs.getLong("watched_ms"), rs.getLong("completions")),
                from.toEpochMilli(), to.toEpochMilli(), scope.param());
    }

    public List<EngagementTotals> engagementTotals(Scope scope, Instant from, Instant to) {
        return jdbcTemplate.query(
                """
                SELECT video_id,
                       countIf(event_type = 'LIKED') - countIf(event_type = 'UNLIKED') AS likes,
                       countIf(event_type = 'COMMENTED') - countIf(event_type = 'UNCOMMENTED') AS comments,
                       countIf(event_type = 'SHARED') AS shares
                FROM engagement_events FINAL
                WHERE %s AND %s AND event_type != 'PUBLISHED'
                GROUP BY video_id
                """.formatted(IN_WINDOW, scope.filter()),
                (rs, i) -> new EngagementTotals(rs.getString("video_id"),
                        rs.getLong("likes"), rs.getLong("comments"), rs.getLong("shares")),
                from.toEpochMilli(), to.toEpochMilli(), scope.param());
    }

    /**
     * How far into the video each view got, in twentieths: index {@code b} counts the views that
     * stopped between {@code b/20} and {@code (b+1)/20} of the length, and index 20 the ones that
     * reached the end. Capped at the length, because a looped video reports more time watched than
     * it lasts.
     *
     * <p>ponytail: watch_events is ordered by time, so one video's history is a full scan. A
     * projection ordered by video_id is the upgrade if the table outgrows it.
     */
    public long[] retentionBuckets(String videoId) {
        long[] buckets = new long[21];
        jdbcTemplate.query(
                """
                SELECT intDiv(least(watched_ms, duration_ms) * 20, duration_ms) AS bucket, count() AS views
                FROM watch_events FINAL
                WHERE video_id = ? AND duration_ms > 0
                GROUP BY bucket
                """,
                rs -> {
                    buckets[(int) rs.getLong("bucket")] = rs.getLong("views");
                },
                videoId);
        return buckets;
    }

    public long viewers(long ownerId, Instant from, Instant to) {
        return jdbcTemplate.queryForObject(
                "SELECT uniqExact(user_id) FROM watch_events FINAL WHERE %s AND user_id > 0 AND %s"
                        .formatted(IN_WINDOW, OWNED_BY),
                Long.class, from.toEpochMilli(), to.toEpochMilli(), ownerId);
    }

    /** Viewers whose first watch of anything this creator posted falls in the window. */
    public long newViewers(long ownerId, Instant from, Instant to) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count() FROM (
                    SELECT user_id
                    FROM watch_events FINAL
                    WHERE occurred_at < fromUnixTimestamp64Milli(?) AND user_id > 0 AND %s
                    GROUP BY user_id
                    HAVING min(occurred_at) >= fromUnixTimestamp64Milli(?)
                )
                """.formatted(OWNED_BY),
                Long.class, to.toEpochMilli(), ownerId, from.toEpochMilli());
    }

    /** Distinct viewers by hour of the day, 0 to 23, in {@link #ZONE}. */
    public long[] viewersByHour(long ownerId, Instant from, Instant to) {
        return viewersBy("toHour(toTimeZone(occurred_at, '%s'))".formatted(ZONE), 0, 24, ownerId, from, to);
    }

    /** Distinct viewers by day of the week, Monday first, in {@link #ZONE}. */
    public long[] viewersByWeekday(long ownerId, Instant from, Instant to) {
        return viewersBy("toDayOfWeek(toTimeZone(occurred_at, '%s'))".formatted(ZONE), 1, 7, ownerId, from, to);
    }

    private long[] viewersBy(String bucket, int first, int size, long ownerId, Instant from, Instant to) {
        long[] counts = new long[size];
        jdbcTemplate.query(
                """
                SELECT %s AS bucket, uniqExact(user_id) AS viewers
                FROM watch_events FINAL
                WHERE %s AND user_id > 0 AND %s
                GROUP BY bucket
                """.formatted(bucket, IN_WINDOW, OWNED_BY),
                rs -> {
                    counts[(int) rs.getLong("bucket") - first] = rs.getLong("viewers");
                },
                from.toEpochMilli(), to.toEpochMilli(), ownerId);
        return counts;
    }
}
