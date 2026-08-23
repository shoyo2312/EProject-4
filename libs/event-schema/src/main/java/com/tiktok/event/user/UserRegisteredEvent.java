package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record UserRegisteredEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        String username,
        String email,
        /*
         * The picture the identity provider already holds for this account, so a social signup does
         * not land on the default avatar. Null for a password signup, and absent from every event
         * produced before this field existed — which deserialises to null, hence null-tolerant on
         * the consumer side.
         */
        String avatarUrl
) implements DomainEvent {

    public static UserRegisteredEvent of(Long userId, String username, String email) {
        return of(userId, username, email, null);
    }

    public static UserRegisteredEvent of(Long userId, String username, String email, String avatarUrl) {
        return new UserRegisteredEvent(
                UUID.randomUUID().toString(), Instant.now(), userId, username, email, avatarUrl);
    }
}
