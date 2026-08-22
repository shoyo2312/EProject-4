package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record CommentDeletedEvent(
        String eventId,
        Instant occurredAt,
        Long commentId,
        Long videoId,
        Long userId
) implements DomainEvent {

    public static CommentDeletedEvent of(Long commentId, Long videoId, Long userId) {
        return new CommentDeletedEvent(UUID.randomUUID().toString(), Instant.now(), commentId, videoId, userId);
    }
}
