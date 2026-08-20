package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * One playback session, reported when it ends. This is the training label for recommendation:
 * short-video ranking is judged on whether people watch to the end, not on whether they like —
 * a like is influenced by the thumbnail and the caption and says little about whether the video
 * held anyone's attention.
 *
 * <p>Deliberately not {@link VideoViewedEvent}, which is the counter's event and is deduplicated
 * to one per viewer per day so viewCount means something. A label needs the opposite: every
 * session, including the third rewatch, because rewatching is the strongest signal there is.
 * The two events answer different questions and cannot share a stream.
 *
 * <p>{@code completed} is decided by the producer rather than left to each consumer, so the
 * threshold lives in one place; {@code watchedMs} and {@code durationMs} ride along because a
 * ranking model wants the ratio, not only the flag.
 */
public record VideoWatchEvent(
        String eventId,
        Instant occurredAt,
        Long videoId,
        Long userId,
        long watchedMs,
        long durationMs,
        boolean completed
) implements DomainEvent {

    public static VideoWatchEvent of(Long videoId, Long userId, long watchedMs, long durationMs, boolean completed) {
        return new VideoWatchEvent(
                UUID.randomUUID().toString(), Instant.now(), videoId, userId, watchedMs, durationMs, completed);
    }
}
