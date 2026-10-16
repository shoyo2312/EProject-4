package com.tiktok.analyticsservice.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/**
 * The two tables the ranking model is trained from. Kept apart from
 * {@link EngagementEventRepository} because nothing reads them over HTTP — they exist to be
 * queried offline by the trainer in {@code services/rank-service}, and the shape that suits a
 * dashboard is not the shape that suits a training set.
 *
 * <p>Times go in as the {@code Instant} itself — see {@link EngagementEventRepository#insertAll}.
 * The trainer cuts its sets at a point in time, so a row stored hours off lands on the wrong side.
 */
@Repository
@RequiredArgsConstructor
public class TrainingDataRepository {

    private final JdbcTemplate jdbcTemplate;

    public record WatchRow(String eventId, String videoId, Long userId,
                           long watchedMs, long durationMs, boolean completed, Instant occurredAt) {
    }

    /**
     * One INSERT for the whole poll. This is the densest stream in the system — a row per playback
     * session — and one INSERT per row is one ClickHouse part per row; see KafkaBatchConfig.
     */
    public void insertWatches(List<WatchRow> rows) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO watch_events
                    (event_id, video_id, user_id, watched_ms, duration_ms, completed, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                rows.stream()
                        .map(row -> new Object[]{row.eventId(), row.videoId(), row.userId(), row.watchedMs(),
                                row.durationMs(), row.completed() ? 1 : 0, row.occurredAt()})
                        .toList());
    }

    /**
     * Writes one row per tag. An untagged video writes nothing: the trainer treats a video with
     * no rows here as having no tags, which is the same answer as a row with an empty tag and
     * one fewer special case.
     */
    public void insertTags(String videoId, List<String> tags, Instant publishedAt) {
        if (tags == null || tags.isEmpty()) {
            // VideoPublishedEvent.of never produces null, but Jackson bypasses that factory and
            // uses the canonical constructor, so an event from a producer older than the tags
            // field deserializes to null here — the same case the visibility field documents.
            return;
        }
        // One INSERT for all of a video's tags, not one each.
        jdbcTemplate.batchUpdate(
                "INSERT INTO video_tags (video_id, tag, published_at) VALUES (?, ?, ?)",
                tags.stream().map(tag -> new Object[]{videoId, tag, publishedAt}).toList());
    }
}
