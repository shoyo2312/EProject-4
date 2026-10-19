package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An account chose a new handle. auth-service owns the handle; user-service keeps a copy for
 * {@code /@handle} lookups and follows it from this event.
 *
 * <p>Its own topic, {@code auth.username-events}: every consumer of {@code auth.user-events}
 * parses straight into {@link UserRegisteredEvent} without reading a header, so this shape there
 * would arrive as a registration.
 */
public record UsernameChangedEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        String username,
        String previousUsername
) implements DomainEvent {

    public static UsernameChangedEvent of(Long userId, String username, String previousUsername) {
        return new UsernameChangedEvent(
                UUID.randomUUID().toString(), Instant.now(), userId, username, previousUsername);
    }
}
