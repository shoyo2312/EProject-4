package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;
import java.time.Instant;
import java.util.UUID;

public record UserBlockChangedEvent(String eventId, Instant occurredAt, Long blockerId,
                                    Long blockedId, boolean blocked) implements DomainEvent {
    public static UserBlockChangedEvent of(Long blockerId, Long blockedId, boolean blocked) {
        return new UserBlockChangedEvent(UUID.randomUUID().toString(), Instant.now(),
                blockerId, blockedId, blocked);
    }
}
