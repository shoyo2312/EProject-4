package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record VideoLikeEvent(
        String eventId,
        Instant occurredAt,
        Long videoId,
        Long userId,
        boolean liked
) implements DomainEvent {

    public static VideoLikeEvent of(Long videoId, Long userId, boolean liked) {
        return new VideoLikeEvent(UUID.randomUUID().toString(), Instant.now(), videoId, userId, liked);
    }
}
