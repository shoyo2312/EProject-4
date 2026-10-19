package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An admin ended every session an account holds without banning it — what is left to do about
 * an account someone else has signed in to, where a ban would lock the owner out along with them.
 * The account stays ACTIVE; its owner signs straight back in.
 */
public record UserSessionsRevokedEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        Long adminId,
        String reason
) implements DomainEvent {

    public static UserSessionsRevokedEvent of(Long userId, Long adminId, String reason) {
        return new UserSessionsRevokedEvent(UUID.randomUUID().toString(), Instant.now(), userId, adminId, reason);
    }
}
