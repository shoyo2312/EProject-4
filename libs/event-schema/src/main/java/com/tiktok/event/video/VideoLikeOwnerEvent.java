package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A realtime-only fan-out signal: "this owner's total-likes may have moved". Never a source of
 * truth — video-service's {@code sumUserVideoStats} stays authoritative, this only tells
 * chat-service which owner to re-read at the next flush. A dropped one costs a stale realtime
 * render until the owner's next like, same tolerance as CommentLikeChangedEvent.
 */
public record VideoLikeOwnerEvent(
        String eventId,
        Instant occurredAt,
        Long videoId,
        Long ownerId
) implements DomainEvent {

    public static VideoLikeOwnerEvent of(Long videoId, Long ownerId) {
        return new VideoLikeOwnerEvent(UUID.randomUUID().toString(), Instant.now(), videoId, ownerId);
    }
}
