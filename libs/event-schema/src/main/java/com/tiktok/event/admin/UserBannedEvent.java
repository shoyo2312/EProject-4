package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * @param bannedUntil when the ban lapses, or null for one that does not. A consumer that ignores
 *                    it still bans the account — the field only says when to stop, and events
 *                    written before temporary bans existed deserialise to null, which is what a
 *                    permanent ban has always meant.
 */
public record UserBannedEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        Long adminId,
        String reason,
        Instant bannedUntil
) implements DomainEvent {

    public static UserBannedEvent of(Long userId, Long adminId, String reason) {
        return of(userId, adminId, reason, null);
    }

    public static UserBannedEvent of(Long userId, Long adminId, String reason, Instant bannedUntil) {
        return new UserBannedEvent(UUID.randomUUID().toString(), Instant.now(), userId, adminId, reason, bannedUntil);
    }
}
