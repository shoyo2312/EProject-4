package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record UserBannedEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        Long adminId,
        String reason
) implements DomainEvent {

    public static UserBannedEvent of(Long userId, Long adminId, String reason) {
        return new UserBannedEvent(UUID.randomUUID().toString(), Instant.now(), userId, adminId, reason);
    }
}
