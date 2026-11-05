package com.tiktok.analyticsservice.repository;

import com.tiktok.analyticsservice.dto.response.SearchQueryResponse;
import com.tiktok.analyticsservice.dto.response.TrafficSourceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    public record DailyEngagement(LocalDate day, long likes, long comments, long shares, long saves) {
    }

    public record DailyFollows(LocalDate day, long gained, long lost) {
    }

    public record WatchTotals(String videoId, long views, long viewers, long watchedMs, long completions) {
    }

    public record EngagementTotals(String videoId, long likes, long comments, long shares, long saves) {
    }

    public record NewViewers(String videoId, long newViewers) {
    }

    /** {@code sourceVideoId} is the video the follow came from, or '' when there is none. */
    public void insertFollow(String eventId, long followerId, long followingId, boolean followed, Instant occurredAt,
                             String sourceVideoId) {
        jdbcTemplate.update(
                """
                INSERT INTO follow_events (event_id, follower_id, following_id, followed, occurred_at, source_video_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                eventId, followerId, followingId, followed ? 1 : 0, occurredAt, sourceVideoId);
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
                       countIf(event_type = 'SHARED') AS shares,
                       countIf(event_type = 'SAVED') - countIf(event_type = 'UNSAVED') AS saves
                FROM engagement_events FINAL
                WHERE %s AND %s AND event_type != 'PUBLISHED'
                GROUP BY day
                """.formatted(ZONE, IN_WINDOW, scope.filter()),
                (rs, i) -> new DailyEngagement(LocalDate.parse(rs.getString("day")),
                        rs.getLong("likes"), rs.getLong("comments"), rs.getLong("shares"), rs.getLong("saves")),
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

    /**
     * Follows the video brought in, gross: a follow later undone still counts, the way TikTok's "new
     * followers" on a video does. The unfollow carries no source, so it cannot be subtracted anyway.
     */
    public long followsFromVideo(String videoId, Instant from, Instant to) {
        return jdbcTemplate.queryForObject(
                "SELECT countIf(followed = 1) FROM follow_events FINAL WHERE source_video_id = ? AND %s"
                        .formatted(IN_WINDOW),
                Long.class, videoId, from.toEpochMilli(), to.toEpochMilli());
    }

    /** {@link #followsFromVideo} by day; {@code lost} is always 0. */
    public List<DailyFollows> dailyFollowsFromVideo(String videoId, Instant from, Instant to) {
        return jdbcTemplate.query(
                """
                SELECT toString(toDate(occurred_at, '%s')) AS day, countIf(followed = 1) AS gained
                FROM follow_events FINAL
                WHERE source_video_id = ? AND %s
                GROUP BY day
                """.formatted(ZONE, IN_WINDOW),
                (rs, i) -> new DailyFollows(LocalDate.parse(rs.getString("day")), rs.getLong("gained"), 0),
                videoId, from.toEpochMilli(), to.toEpochMilli());
    }

    /** Where one video's views in the window came from, busiest surface first; surfaces with no views are left out. */
    public List<TrafficSourceResponse> trafficSources(String videoId, Instant from, Instant to) {
        return jdbcTemplate.query(
                """
                SELECT source, count() AS views
                FROM watch_events FINAL
                WHERE video_id = ? AND %s
                GROUP BY source
                ORDER BY views DESC, source
                """.formatted(IN_WINDOW),
                (rs, i) -> new TrafficSourceResponse(rs.getString("source"), rs.getLong("views")),
                videoId, from.toEpochMilli(), to.toEpochMilli());
    }

    /**
     * What viewers searched to find the video, most viewed first. A query under {@code minViews}
     * is left out: with one or two views it names the viewer more than the audience.
     */
    public List<SearchQueryResponse> searchQueries(String videoId, Instant from, Instant to, int minViews, int limit) {
        return jdbcTemplate.query(
                """
                SELECT search_query, count() AS views
                FROM watch_events FINAL
                WHERE video_id = ? AND %s AND source = 'SEARCH' AND search_query != ''
                GROUP BY search_query
                HAVING views >= ?
                ORDER BY views DESC, search_query
                LIMIT ?
                """.formatted(IN_WINDOW),
                (rs, i) -> new SearchQueryResponse(rs.getString("search_query"), rs.getLong("views")),
                videoId, from.toEpochMilli(), to.toEpochMilli(), minViews, limit);
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
                       countIf(event_type = 'SHARED') AS shares,
                       countIf(event_type = 'SAVED') - countIf(event_type = 'UNSAVED') AS saves
                FROM engagement_events FINAL
                WHERE %s AND %s AND event_type != 'PUBLISHED'
                GROUP BY video_id
                """.formatted(IN_WINDOW, scope.filter()),
                (rs, i) -> new EngagementTotals(rs.getString("video_id"),
                        rs.getLong("likes"), rs.getLong("comments"), rs.getLong("shares"), rs.getLong("saves")),
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

    /**
     * {@link #newViewers} split by the video each new viewer came in through: the first of this
     * creator's videos they ever watched. One video per viewer, so the counts add up to
     * {@link #newViewers} for the same window.
     */
    public List<NewViewers> newViewersByVideo(long ownerId, Instant from, Instant to) {
        return jdbcTemplate.query(
                """
                SELECT first_video, count() AS new_viewers FROM (
                    SELECT user_id, argMin(video_id, occurred_at) AS first_video
                    FROM watch_events FINAL
                    WHERE occurred_at < fromUnixTimestamp64Milli(?) AND user_id > 0 AND %s
                    GROUP BY user_id
                    HAVING min(occurred_at) >= fromUnixTimestamp64Milli(?)
                )
                GROUP BY first_video
                """.formatted(OWNED_BY),
                (rs, i) -> new NewViewers(rs.getString("first_video"), rs.getLong("new_viewers")),
                to.toEpochMilli(), ownerId, from.toEpochMilli());
    }

    /** {@link #newViewersByVideo} for one video: new viewers whose first watch of this creator was it. */
    public long newViewersOfVideo(long ownerId, String videoId, Instant from, Instant to) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count() FROM (
                    SELECT user_id, argMin(video_id, occurred_at) AS first_video
                    FROM watch_events FINAL
                    WHERE occurred_at < fromUnixTimestamp64Milli(?) AND user_id > 0 AND %s
                    GROUP BY user_id
                    HAVING min(occurred_at) >= fromUnixTimestamp64Milli(?) AND first_video = ?
                )
                """.formatted(OWNED_BY),
                Long.class, to.toEpochMilli(), ownerId, from.toEpochMilli(), videoId);
    }

    /**
     * Distinct viewers of the video in the window who were following its owner at the window's
     * end: their latest follow row toward the owner before {@code to} is a follow, not an unfollow.
     */
    public long followerViewers(long ownerId, String videoId, Instant from, Instant to) {
        return jdbcTemplate.queryForObject(
                """
                SELECT count() FROM (
                    SELECT DISTINCT user_id FROM watch_events FINAL
                    WHERE video_id = ? AND %s AND user_id > 0
                ) AS v
                INNER JOIN (
                    SELECT follower_id, argMax(followed, occurred_at) AS latest
                    FROM follow_events FINAL
                    WHERE following_id = ? AND occurred_at < fromUnixTimestamp64Milli(?)
                    GROUP BY follower_id
                    HAVING latest = 1
                ) AS f ON f.follower_id = v.user_id
                """.formatted(IN_WINDOW),
                Long.class, videoId, from.toEpochMilli(), to.toEpochMilli(), ownerId, to.toEpochMilli());
    }

    /**
     * Distinct viewers of the video in the window by age group, the age being full years at their
     * first watch in the window. Only groups with viewers come back; a viewer without a birth date
     * is {@code UNKNOWN}. {@code age()} rather than {@code dateDiff('year')}: the latter counts
     * year boundaries, which would make everyone a year older from New Year to their birthday.
     */
    public Map<String, Long> viewersByAge(String videoId, Instant from, Instant to) {
        Map<String, Long> groups = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT multiIf(isNull(years), 'UNKNOWN',
                               years < 18, '13-17',
                               years < 25, '18-24',
                               years < 35, '25-34',
                               years < 45, '35-44',
                               years < 55, '45-54',
                               '55+') AS age_group,
                       count() AS viewers
                FROM (
                    SELECT w.user_id, age('year', p.birth_date, toDate(min(w.occurred_at), '%s')) AS years
                    FROM (
                        SELECT user_id, occurred_at FROM watch_events FINAL
                        WHERE video_id = ? AND %s AND user_id > 0
                    ) AS w
                    LEFT JOIN (SELECT user_id, birth_date FROM viewer_profiles FINAL) AS p ON p.user_id = w.user_id
                    GROUP BY w.user_id, p.birth_date
                )
                GROUP BY age_group
                """.formatted(ZONE, IN_WINDOW),
                rs -> {
                    groups.put(rs.getString("age_group"), rs.getLong("viewers"));
                },
                videoId, from.toEpochMilli(), to.toEpochMilli());
        return groups;
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
