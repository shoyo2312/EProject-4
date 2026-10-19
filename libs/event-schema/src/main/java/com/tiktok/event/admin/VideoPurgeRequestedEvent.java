package com.tiktok.event.admin;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * An admin emptied one video out of the trash before its retention window ran out. video-service
 * applies it by making the video due for its VideoPurgedEvent now rather than at deletedAt plus
 * {@code video.trash.retention}; a video that is not in the trash is left alone.
 */
public record VideoPurgeRequestedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long adminId,
        String reason
) implements DomainEvent {

    public static VideoPurgeRequestedEvent of(String videoId, Long adminId, String reason) {
        return new VideoPurgeRequestedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, adminId, reason);
    }
}
