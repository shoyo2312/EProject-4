package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record VideoPublishedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long userId,
        String title,
        String rawFileUrl
) implements DomainEvent {

    public static VideoPublishedEvent of(String videoId, Long userId, String title, String rawFileUrl) {
        return new VideoPublishedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, userId, title, rawFileUrl);
    }
}
