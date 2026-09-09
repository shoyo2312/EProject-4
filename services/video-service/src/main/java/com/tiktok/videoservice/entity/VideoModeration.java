package com.tiktok.videoservice.entity;

import com.tiktok.event.video.ModerationVerdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * What automatic moderation said about a video, stored on the document rather than only logged.
 *
 * <p>The thresholds that turned these numbers into a verdict are going to be tuned, and tuning
 * them means asking questions like "of the videos a moderator later approved, what did the model
 * score them" — which is unanswerable unless the score is still attached to the video. It also
 * lets an admin looking at a PENDING_REVIEW video see how close a call it was.
 *
 * <p>Nested inside Video rather than a collection of its own: it is written once, read with the
 * video, and never queried without it.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VideoModeration {

    private ModerationVerdict verdict;

    /** The model's label behind {@code maxScore}, e.g. {@code nsfw}. Null when nothing ran. */
    private String label;

    /** The highest score any sampled frame scored for {@code label}. */
    private double maxScore;

    /** How many sampled frames scored above the review threshold. */
    private int suspiciousFrames;

    private int totalFrames;

    private String model;

    /**
     * Which build of moderation-service decided this. Without it a verdict cannot be attributed
     * to a set of weights and thresholds once either has been changed.
     */
    private String modelVersion;

    /** Set when the verdict is REVIEW because the check could not be completed. */
    private String reason;

    private Instant checkedAt;
}
