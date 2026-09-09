package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * The owner moved a video between PUBLIC, FRIENDS and PRIVATE. Published on the same topic and
 * under the same key as {@link VideoPublishedEvent}, so Kafka orders the two per video and a
 * consumer never applies a visibility change to a video it has not been told about.
 *
 * <p>It exists because visibility rides on the publication event, and the publication is sent
 * once. Without this, a video indexed while it was PUBLIC stays searchable by anyone for as long
 * as the index exists, however many times its owner makes it private afterwards.
 */
public record VideoVisibilityChangedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long userId,
        /** PUBLIC, FRIENDS or PRIVATE, as video-service's VideoVisibility names them. */
        String visibility
) implements DomainEvent {

    /**
     * Derived from the videoId and the moment the change was recorded, not drawn at random: the
     * outbox poll can send the same change twice — an acknowledgement that times out leaves the
     * row unmarked even when the broker did receive it — and a fresh UUID on the retry makes the
     * duplicate unrecognisable to consumers that deduplicate on eventId.
     *
     * <p>{@code changedAt} is in the identifier because a video's visibility moves as often as
     * its owner likes: PUBLIC → PRIVATE → PUBLIC is three separate facts, and an id built from
     * the videoId and the value alone would make the third indistinguishable from the first, so
     * a consumer that already saw the first would drop it.
     */
    public static VideoVisibilityChangedEvent of(String videoId, Long userId, String visibility,
                                                 Instant changedAt) {
        String eventId = UUID.nameUUIDFromBytes(
                ("VideoVisibilityChangedEvent:" + videoId + ":" + changedAt.toEpochMilli())
                        .getBytes(StandardCharsets.UTF_8)).toString();
        return new VideoVisibilityChangedEvent(eventId, Instant.now(), videoId, userId, visibility);
    }
}
