package com.tiktok.event.interaction;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A viewer added a video to their favourites, or took it back out. Same shape as
 * {@link VideoLikeEvent} and keyed by video for the same reason: one video's saves stay ordered
 * within a partition, so a save and the unsave that follows it cannot be applied out of order.
 */
public record VideoSavedEvent(
        String eventId,
        Instant occurredAt,
        Long videoId,
        Long userId,
        boolean saved
) implements DomainEvent {

    public static VideoSavedEvent of(Long videoId, Long userId, boolean saved) {
        return new VideoSavedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, userId, saved);
    }
}
