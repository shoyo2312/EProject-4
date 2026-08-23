package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A social sign-in has told us where the identity provider keeps this account's picture.
 *
 * <p>Published on every social sign-in that carries one, not only the first. The URL belongs to the
 * provider and Facebook's expires within days, so a picture that loaded when the account was
 * created stops loading later; seeing it again on each login is what lets media-worker copy the
 * bytes into our own storage, and what backfills an account that signed up before any of this
 * existed.
 *
 * @param avatarUrl where the provider serves it. Always https, and always a host media-worker's own
 *                  allow-list has to accept before it fetches anything.
 */
public record SocialAvatarDiscoveredEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        String avatarUrl
) implements DomainEvent {

    public static SocialAvatarDiscoveredEvent of(Long userId, String avatarUrl) {
        return new SocialAvatarDiscoveredEvent(UUID.randomUUID().toString(), Instant.now(), userId, avatarUrl);
    }
}
