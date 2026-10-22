package com.tiktok.event.story;

import com.tiktok.event.DomainEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * A story is ACTIVE with a visibility beyond its owner (PUBLIC or FRIENDS) and has not been
 * announced yet, so the people who may watch it should be told. Usually that is when it goes
 * live; a story posted PRIVATE is announced when its owner widens it. Published by
 * story-service from its outbox on {@code story.activation-events}, keyed by the owner's id, and
 * consumed by notification-service.
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

    /**
     * Derived from the storyId: the activation poll can legitimately send the same story twice,
     * since an acknowledgement that times out leaves the row unmarked and every replica runs the
     * poll. A fresh UUID on the retry would hide the duplicate from consumers that deduplicate on
     * eventId. A story is announced at most once, so the identifier names that fact.
     *
     * <p>Prefixed with the event name, so it cannot collide with another event about the same
     * story in one processed-events table.
     */
    public static StoryActivatedEvent of(String storyId, Long userId) {
        String eventId = UUID.nameUUIDFromBytes(
                ("StoryActivatedEvent:" + storyId).getBytes(StandardCharsets.UTF_8)).toString();
        return new StoryActivatedEvent(eventId, Instant.now(), storyId, userId);
    }
}
