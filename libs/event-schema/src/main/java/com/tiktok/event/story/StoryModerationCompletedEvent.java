package com.tiktok.event.story;

import com.tiktok.event.DomainEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * media-worker's verdict on a story's media, consumed by story-service. The same scores as
 * {@link VideoModerationCompletedEvent}, kept for the same reason: thresholds are tuned against
 * what the model said. REVIEW hides the story until it expires — there is no admin queue for
 * stories.
 *
 * @param durationSeconds the probed length of a video story, which the viewer's progress bar needs;
 *                        null for an image
 */
public record StoryModerationCompletedEvent(
        String eventId,
        Instant occurredAt,
        String storyId,
        ModerationVerdict verdict,
        String label,
        double maxScore,
        int suspiciousFrames,
        int totalFrames,
        long processingTimeMs,
        String model,
        String modelVersion,
        String reason,
        Integer durationSeconds
) implements DomainEvent {

    /** media-worker scores a story with the video machinery; this re-addresses that result. */
    public static StoryModerationCompletedEvent from(String storyId, VideoModerationCompletedEvent frames,
                                                     Integer durationSeconds) {
        return new StoryModerationCompletedEvent(UUID.randomUUID().toString(), Instant.now(), storyId,
                frames.verdict(), frames.label(), frames.maxScore(), frames.suspiciousFrames(),
                frames.totalFrames(), frames.processingTimeMs(), frames.model(), frames.modelVersion(),
                frames.reason(), durationSeconds);
    }
}
