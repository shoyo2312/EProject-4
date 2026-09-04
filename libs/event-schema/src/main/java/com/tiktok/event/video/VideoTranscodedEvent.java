package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.time.Instant;
import java.util.UUID;

public record VideoTranscodedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        boolean success,
        String thumbnailUrl,
        /** Animated hover preview; null when none could be produced, and the client shows the still. */
        String previewUrl,
        String hlsUrl,
        Integer durationSeconds,
        String failureReason
) implements DomainEvent {

    public static VideoTranscodedEvent success(String videoId, String thumbnailUrl, String previewUrl,
                                               String hlsUrl, Integer durationSeconds) {
        return new VideoTranscodedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, true,
                thumbnailUrl, previewUrl, hlsUrl, durationSeconds, null);
    }

    public static VideoTranscodedEvent failure(String videoId, String reason) {
        return new VideoTranscodedEvent(
                UUID.randomUUID().toString(), Instant.now(), videoId, false, null, null, null, null, reason);
    }
}
