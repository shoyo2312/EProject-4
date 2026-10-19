package com.tiktok.analyticsservice.repository;

import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class EngagementEventRepository {

    private static final String INSERT =
            "INSERT INTO engagement_events (event_id, event_type, video_id, user_id, occurred_at) VALUES (?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public record Row(String eventId, String eventType, String videoId, Long userId, Instant occurredAt) {
    }

    /** For the topics that see a record at a time; see {@link #insertAll} for the busy ones. */
    public void insert(String eventId, String eventType, String videoId, Long userId, Instant occurredAt) {
        insertAll(List.of(new Row(eventId, eventType, videoId, userId, occurredAt)));
    }

    /**
     * One INSERT, and so one part, for all of them. The driver sends a JDBC batch as a single
     * request, where one update per row wrote one part per row — see KafkaBatchConfig.
     *
     * <p>The time is bound as the {@code Instant} itself, which the driver converts in the zone
     * the server reads it in. A {@code Timestamp} it writes as the JVM's wall-clock time instead,
     * so with the JVM and ClickHouse in different zones every row was stored off by the gap.
     */
    public void insertAll(List<Row> rows) {
        jdbcTemplate.batchUpdate(INSERT, rows.stream()
                .map(row -> new Object[]{row.eventId(), row.eventType(), row.videoId(), row.userId(),
                        row.occurredAt()})
                .toList());
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
                    countIf(event_type = 'COMMENTED') - countIf(event_type = 'UNCOMMENTED') AS comments,
                    countIf(event_type = 'SHARED') AS shares
                FROM engagement_events FINAL
                WHERE video_id = ?
                """,
                (rs, rowNum) -> new VideoEngagementSummaryResponse(videoId, rs.getLong("likes"), rs.getLong("comments"), rs.getLong("shares")),
                videoId);
    }
}
