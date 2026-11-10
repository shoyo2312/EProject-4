package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The owner edited a video's title or description from TikTok Studio. Published on the same topic
 * and under the same key as {@link VideoPublishedEvent}, so Kafka orders the two per video and a
 * consumer never applies an edit to a video it has not been told about.
 *
 * <p>It exists for the same reason as {@link VideoVisibilityChangedEvent}: the caption rides on the
 * publication, which is sent once, and search-service and recommendation-service keep their own
 * copy of it. Without this an edited video stays findable by its old words, and its old tags keep
 * feeding the tag pages and the content profile.
 *
 * <p>Carries the caption as it is now, not the edit that queued it — two quick edits announce the
 * second one twice rather than the first one last.
 */
public record VideoCaptionChangedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long userId,
        String title,
        /** Null when the owner cleared it. */
        String description,
        /** Normalised as on {@link VideoPublishedEvent#tags()}: typed tags plus the description's hashtags. */
        List<String> tags
) implements DomainEvent {

    /**
     * Derived from the videoId and the moment the edit was recorded, for the reason given on
     * {@link VideoVisibilityChangedEvent#of}: a resend of the same edit must keep its id, and a
     * later edit back to the same words must not.
     */
    public static VideoCaptionChangedEvent of(String videoId, Long userId, String title, String description,
                                              List<String> tags, Instant changedAt) {
        String eventId = UUID.nameUUIDFromBytes(
                ("VideoCaptionChangedEvent:" + videoId + ":" + changedAt.toEpochMilli())
                        .getBytes(StandardCharsets.UTF_8)).toString();
        return new VideoCaptionChangedEvent(eventId, Instant.now(), videoId, userId, title, description, tags);
    }
}
