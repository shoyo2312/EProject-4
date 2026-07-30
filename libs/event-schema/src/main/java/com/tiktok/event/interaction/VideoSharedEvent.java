package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record VideoSharedEvent(
        String eventId,
        Instant occurredAt,
        Long shareId,
        Long videoId,
        Long userId
) implements DomainEvent {

    public static VideoSharedEvent of(Long shareId, Long videoId, Long userId) {
        return new VideoSharedEvent(UUID.randomUUID().toString(), Instant.now(), shareId, videoId, userId);
    }
}
