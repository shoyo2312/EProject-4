package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An account became private, or public again. video-service keeps its own copy of the flag and
 * re-announces the owner's videos with the audience that follows from it.
 *
 * <p>{@code occurredAt} is when the change was made, and the consumer compares it with the copy it
 * holds: an older event arriving after a newer change, such as a re-send from user-service's
 * outbox, must not undo that change. The consumer takes no claim on {@code eventId} and retries a
 * failure until it succeeds, so a switch is never dropped; every step it takes is safe to repeat.
 */
public record UserPrivacyChangedEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        boolean privateAccount
) implements DomainEvent {

    public static UserPrivacyChangedEvent of(Long userId, boolean privateAccount) {
        return new UserPrivacyChangedEvent(UUID.randomUUID().toString(), Instant.now(), userId, privateAccount);
    }
}
