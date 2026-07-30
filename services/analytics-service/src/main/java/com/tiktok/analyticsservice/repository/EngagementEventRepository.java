package com.tiktok.analyticsservice.repository;

import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class EngagementEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insert(String eventId, String eventType, String videoId, Long userId, Instant occurredAt) {
        jdbcTemplate.update(
                "INSERT INTO engagement_events (event_id, event_type, video_id, user_id, occurred_at) VALUES (?, ?, ?, ?, ?)",
                eventId, eventType, videoId, userId, Timestamp.from(occurredAt));
    }

    public List<DailyCountResponse> findDailyCounts(int days) {
        return jdbcTemplate.query(
                """
                SELECT toDate(occurred_at) AS day, event_type, count() AS cnt
                FROM engagement_events FINAL
                WHERE occurred_at >= now() - INTERVAL ? DAY
                GROUP BY day, event_type
                ORDER BY day, event_type
                """,
                (rs, rowNum) -> new DailyCountResponse(rs.getDate("day").toLocalDate(), rs.getString("event_type"), rs.getLong("cnt")),
                days);
    }

    public VideoEngagementSummaryResponse findSummaryByVideoId(String videoId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT
                    countIf(event_type = 'LIKED') - countIf(event_type = 'UNLIKED') AS likes,
                    countIf(event_type = 'COMMENTED') AS comments,
                    countIf(event_type = 'SHARED') AS shares
                FROM engagement_events FINAL
                WHERE video_id = ?
                """,
                (rs, rowNum) -> new VideoEngagementSummaryResponse(videoId, rs.getLong("likes"), rs.getLong("comments"), rs.getLong("shares")),
                videoId);
    }
}
