package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record UserUnbannedEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        Long adminId,
        String reason
) implements DomainEvent {

    public static UserUnbannedEvent of(Long userId, Long adminId, String reason) {
        return new UserUnbannedEvent(UUID.randomUUID().toString(), Instant.now(), userId, adminId, reason);
    }
}
