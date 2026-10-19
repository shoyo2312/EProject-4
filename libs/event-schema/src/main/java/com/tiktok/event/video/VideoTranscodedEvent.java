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
        /**
         * Display width and height, with the container's rotation already applied — the shape a
         * player will draw. Null on the failure path, on events produced before these existed,
         * and when the file could not be measured; a client with no numbers falls back to its own
         * default ratio, which is what every video got before these were carried at all.
         */
        Integer width,
        Integer height,
        String failureReason,
        /**
         * VideoPublishedEvent's verdict on the caption, carried through so the moderation step —
         * which reads this event, not the publication — can fold it into its own. Null on the
         * failure path, from producers older than the field, and when nothing screened the caption.
         */
        ModerationVerdict textVerdict
) implements DomainEvent {

    public static VideoTranscodedEvent success(String videoId, String thumbnailUrl, String previewUrl,
                                               String hlsUrl, Integer durationSeconds,
                                               Integer width, Integer height) {
        return new VideoTranscodedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, true,
                thumbnailUrl, previewUrl, hlsUrl, durationSeconds, width, height, null, null);
    }

    /**
     * Without dimensions, for callers that have none to give — the consumers outside video-service
     * ignore them, and so do the tests that build events this way. The transcode pipeline always
     * has them and calls the overload above.
     */
    public static VideoTranscodedEvent success(String videoId, String thumbnailUrl, String previewUrl,
                                               String hlsUrl, Integer durationSeconds) {
        return success(videoId, thumbnailUrl, previewUrl, hlsUrl, durationSeconds, null, null);
    }

    public static VideoTranscodedEvent failure(String videoId, String reason) {
        return new VideoTranscodedEvent(UUID.randomUUID().toString(), Instant.now(), videoId, false,
                null, null, null, null, null, null, reason, null);
    }

    /** The same result, carrying the caption's verdict from the publication it answers. */
    public VideoTranscodedEvent withTextVerdict(ModerationVerdict verdict) {
        return new VideoTranscodedEvent(eventId, occurredAt, videoId, success, thumbnailUrl, previewUrl,
                hlsUrl, durationSeconds, width, height, failureReason, verdict);
    }
}
