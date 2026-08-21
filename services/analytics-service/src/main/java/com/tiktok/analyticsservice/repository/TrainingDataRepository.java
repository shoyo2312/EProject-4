package com.tiktok.analyticsservice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * The two tables the ranking model is trained from. Kept apart from
 * {@link EngagementEventRepository} because nothing reads them over HTTP — they exist to be
 * queried offline by the trainer in {@code services/rank-service}, and the shape that suits a
 * dashboard is not the shape that suits a training set.
 */
@Repository
@RequiredArgsConstructor
public class TrainingDataRepository {

    private final JdbcTemplate jdbcTemplate;

    public void insertWatch(String eventId, String videoId, Long userId,
                            long watchedMs, long durationMs, boolean completed, Instant occurredAt) {
        jdbcTemplate.update(
                """
                INSERT INTO watch_events
                    (event_id, video_id, user_id, watched_ms, duration_ms, completed, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                eventId, videoId, userId, watchedMs, durationMs, completed ? 1 : 0, Timestamp.from(occurredAt));
    }

    /**
     * Writes one row per tag. An untagged video writes nothing: the trainer treats a video with
     * no rows here as having no tags, which is the same answer as a row with an empty tag and
     * one fewer special case.
     */
    public void insertTags(String videoId, List<String> tags, Instant publishedAt) {
        for (String tag : tags) {
            jdbcTemplate.update(
                    "INSERT INTO video_tags (video_id, tag, published_at) VALUES (?, ?, ?)",
                    videoId, tag, Timestamp.from(publishedAt));
        }
    }
}
