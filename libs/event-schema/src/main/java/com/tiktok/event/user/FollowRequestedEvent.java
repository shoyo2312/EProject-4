package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Someone asked to follow a private account. Not a follow: until the owner approves there is no
 * edge, and the approval arrives as a {@link UserFollowChangedEvent} with {@code requestApproved}.
 * A withdrawn or rejected request is not announced.
 */
public record FollowRequestedEvent(
        String eventId,
        Instant occurredAt,
        Long requesterId,
        Long targetId
) implements DomainEvent {

    public static FollowRequestedEvent of(Long requesterId, Long targetId) {
        return new FollowRequestedEvent(UUID.randomUUID().toString(), Instant.now(), requesterId, targetId);
    }
}
