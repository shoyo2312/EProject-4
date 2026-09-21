package com.tiktok.analyticsservice.repository;

import com.tiktok.analyticsservice.dto.response.DailyActiveUsersResponse;
import com.tiktok.analyticsservice.dto.response.TopVideoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Reads over watch_events. The writes live in {@link TrainingDataRepository} — that table exists
 * for the ranker, and these two queries are the admin console borrowing it, which is why nothing
 * here inserts.
 *
 * <p>Both use FINAL. watch_events is a ReplacingMergeTree and a redelivered Kafka event writes a
 * second row with the same event_id, so without it a replayed hour inflates every number on the
 * screen and nothing says it did.
 */
@Repository
@RequiredArgsConstructor
public class WatchEventRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Distinct viewers per day. {@code user_id > 0} drops anonymous playback, which arrives with
     * no account attached and would otherwise collapse into one very busy user.
     */
    public List<DailyActiveUsersResponse> findDailyActiveUsers(int days) {
        return jdbcTemplate.query(
                """
                SELECT toDate(occurred_at) AS day, uniqExact(user_id) AS active_users
                FROM watch_events FINAL
                WHERE occurred_at >= now() - INTERVAL ? DAY AND user_id > 0
                GROUP BY day
                ORDER BY day
                """,
                (rs, rowNum) -> new DailyActiveUsersResponse(
                        rs.getDate("day").toLocalDate(), rs.getLong("active_users")),
                days);
    }

    /** The most-watched videos of the window, by time spent rather than by view count. */
    public List<TopVideoResponse> findTopVideos(int days, int limit) {
        return jdbcTemplate.query(
                """
                SELECT video_id,
                       count() AS views,
                       sum(watched_ms) AS watched_ms,
                       countIf(completed = 1) AS completions,
                       uniqExact(user_id) AS viewers
                FROM watch_events FINAL
                WHERE occurred_at >= now() - INTERVAL ? DAY
                GROUP BY video_id
                ORDER BY watched_ms DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new TopVideoResponse(
                        rs.getString("video_id"),
                        rs.getLong("views"),
                        rs.getLong("watched_ms"),
                        rs.getLong("completions"),
                        rs.getLong("viewers")),
                days, limit);
    }
}
