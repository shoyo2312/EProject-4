package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * A video was removed by its owner. Published on the same topic and under the same key as
 * {@link VideoPublishedEvent}, so Kafka orders the pair per video: a consumer can never be handed
 * the removal of a video it has not been told about yet.
 *
 * <p>Sent as soon as the owner deletes: this is what takes the video out of search results and
 * the recommendation feed. Its media is not erased on this event — that is
 * {@link VideoPurgedEvent}, which follows once the trash window has run out.
 *
 * <p>Emitted for a video deleted before its VideoPublishedEvent ever went out as well, so every
 * consumer must treat an unknown videoId as a no-op.
 */
public record VideoDeletedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long userId,
        // Kept for consumers that already read it; the purge itself is driven by
        // VideoPurgedEvent, which carries the same key.
        String rawFileUrl
) implements DomainEvent {

    /**
     * Derived from the videoId for the same reason {@link VideoPublishedEvent#of} derives its
     * own: the outbox poll can legitimately send the same deletion twice — an acknowledgement
     * that times out leaves the row unmarked even when the broker did receive it — and a fresh
     * UUID on the retry makes the duplicate unrecognisable to consumers that deduplicate on
     * eventId. A video is deleted exactly once, so the identifier names that fact.
     *
     * <p>Prefixed with the event name, so the deletion of a video does not collide with its own
     * publication in a consumer that keeps both in one processed-events table.
     */
    public static VideoDeletedEvent of(String videoId, Long userId, String rawFileUrl) {
        String eventId = UUID.nameUUIDFromBytes(
                ("VideoDeletedEvent:" + videoId).getBytes(StandardCharsets.UTF_8)).toString();
        return new VideoDeletedEvent(eventId, Instant.now(), videoId, userId, rawFileUrl);
    }
}
