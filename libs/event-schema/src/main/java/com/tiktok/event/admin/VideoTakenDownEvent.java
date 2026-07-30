package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record VideoTakenDownEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long adminId,
        String reason
) implements DomainEvent {

    public static VideoTakenDownEvent of(String videoId, Long adminId, String reason) {
        return new VideoTakenDownEvent(UUID.randomUUID().toString(), Instant.now(), videoId, adminId, reason);
    }
}
