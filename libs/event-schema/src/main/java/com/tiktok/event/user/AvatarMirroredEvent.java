package com.tiktok.event.user;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * media-worker has copied a provider's picture into our own storage, and this says where it landed.
 *
 * @param sourceUrl the provider URL the copy was made from. Carried so user-service can replace
 *                  exactly that value and nothing else: a profile whose avatar is neither empty nor
 *                  this source has had a picture chosen by its owner, and that one is never
 *                  overwritten by a copy of an old provider photo.
 * @param avatarUrl the object in our own storage, which is what the profile points at from then on
 *                  — it does not expire, and it stops every page view from announcing the user to
 *                  the provider's CDN.
 */
public record AvatarMirroredEvent(
        String eventId,
        Instant occurredAt,
        Long userId,
        String sourceUrl,
        String avatarUrl
) implements DomainEvent {

    public static AvatarMirroredEvent of(Long userId, String sourceUrl, String avatarUrl) {
        return new AvatarMirroredEvent(
                UUID.randomUUID().toString(), Instant.now(), userId, sourceUrl, avatarUrl);
    }
}
