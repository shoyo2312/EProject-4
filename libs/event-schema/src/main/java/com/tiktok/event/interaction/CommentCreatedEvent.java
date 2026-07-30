package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record CommentCreatedEvent(
        String eventId,
        Instant occurredAt,
        Long commentId,
        Long videoId,
        Long userId,
        String content
) implements DomainEvent {

    public static CommentCreatedEvent of(Long commentId, Long videoId, Long userId, String content) {
        return new CommentCreatedEvent(UUID.randomUUID().toString(), Instant.now(), commentId, videoId, userId, content);
    }
}
