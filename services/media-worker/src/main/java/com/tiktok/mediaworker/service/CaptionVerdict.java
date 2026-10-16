package com.tiktok.mediaworker.service;

import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;

/**
 * Folds video-service's verdict on a video's caption into the verdict on its frames, so the one
 * VideoModerationCompletedEvent every consumer reads already says "hold" when only the caption was
 * the problem.
 *
 * <p>Here, not in video-service, because video-service is not the only reader of the verdict:
 * search-service and recommendation-service publish a video on APPROVED themselves. A video held in
 * video-service for its caption alone would still be searchable and among the For You candidates.
 *
 * <p>Text can hold a video, never remove one: a held caption turns APPROVED into REVIEW and leaves
 * REVIEW and REJECTED as they are. The frames' scores travel unchanged — they are still what the
 * classifier said, and an admin weighing the video wants both halves.
 */
public final class CaptionVerdict {

    static final String REASON = "Caption held for review by text moderation";

    private CaptionVerdict() {
    }

    /**
     * @param caption null when the publication carried no verdict — an older video-service, or text
     *                moderation not enforced — which leaves the frames' verdict as it is
     */
    public static VideoModerationCompletedEvent combine(VideoModerationCompletedEvent frames, ModerationVerdict caption) {
        boolean captionHeld = caption == ModerationVerdict.REVIEW || caption == ModerationVerdict.REJECTED;
        if (!captionHeld || frames.verdict() != ModerationVerdict.APPROVED) {
            return frames;
        }
        String reason = frames.reason() == null ? REASON : REASON + " (" + frames.reason() + ")";
        return VideoModerationCompletedEvent.of(frames.videoId(), ModerationVerdict.REVIEW, frames.label(),
                frames.maxScore(), frames.suspiciousFrames(), frames.totalFrames(), frames.processingTimeMs(),
                frames.model(), frames.modelVersion(), reason);
    }
}
