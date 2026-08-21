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
 * <p>Only ever emitted for a video whose VideoPublishedEvent actually went out. A video deleted
 * inside the five-second window before the outbox poll picked it up was never announced to
 * anyone, and announcing its removal would ask every consumer to delete something it does not
 * have.
 */
public record VideoDeletedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long userId,
        // Carried so media-worker can remove the source object along with the derived ones. It is
        // the only party that knows the bucket, and the only thing that knows the raw key is this
        // event — once the document is gone there is nothing left to look it up from.
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
