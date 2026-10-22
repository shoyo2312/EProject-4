package com.tiktok.event.story;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A story became ACTIVE with a visibility its followers can see, so its owner's ring and their
 * followers' notifications should follow. Published by story-service from its outbox on
 * {@code story.activation-events}, keyed by the owner's id, and consumed by notification-service.
 *
 * <p>Carries no visibility on purpose: the consumer reads the story's current visibility, and a
 * second copy here would invite acting on a stale one.
 */
public record StoryActivatedEvent(
        String eventId,
        Instant occurredAt,
        String storyId,
        Long userId
) implements DomainEvent {

    public static StoryActivatedEvent of(String storyId, Long userId) {
        return new StoryActivatedEvent(UUID.randomUUID().toString(), Instant.now(), storyId, userId);
    }
}
