package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A follow edge was created or removed. Both ids' counts moved: followerId's followingCount,
 * followingId's followerCount — consumers that only care about one side still read both fields,
 * same shape as VideoLikeEvent carrying the liker rather than two separate topics.
 */
public record UserFollowChangedEvent(
        String eventId,
        Instant occurredAt,
        Long followerId,
        Long followingId,
        boolean followed
) implements DomainEvent {

    public static UserFollowChangedEvent of(Long followerId, Long followingId, boolean followed) {
        return new UserFollowChangedEvent(UUID.randomUUID().toString(), Instant.now(), followerId, followingId, followed);
    }
}
