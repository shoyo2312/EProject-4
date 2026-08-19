package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * One view that actually counted. interaction-service only emits this when the view survived
 * its own deduplication window, so a consumer can treat every event as a straight +1 rather
 * than having to know how views are deduplicated.
 */
public record VideoViewedEvent(
        String eventId,
        Instant occurredAt,
        Long videoId,
        Long userId
) implements DomainEvent {

    public static VideoViewedEvent of(Long videoId, Long userId) {
        return new VideoViewedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, userId);
    }
}
