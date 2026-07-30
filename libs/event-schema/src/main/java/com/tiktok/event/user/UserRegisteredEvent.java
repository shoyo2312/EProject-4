package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record UserRegisteredEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        String username,
        String email
) implements DomainEvent {

    public static UserRegisteredEvent of(Long userId, String username, String email) {
        return new UserRegisteredEvent(UUID.randomUUID().toString(), Instant.now(), userId, username, email);
    }
}
