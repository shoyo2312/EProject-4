package com.tiktok.storyservice.entity;

import com.tiktok.event.video.ModerationVerdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * What media-worker's classifier said about a story, kept on the document for the reason
 * video-service keeps VideoModeration: thresholds are tuned against what the model scored.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StoryModeration {

    private ModerationVerdict verdict;

    private String label;

    private double maxScore;

    private int suspiciousFrames;

    private int totalFrames;

    private String model;

    private String modelVersion;

    /** Set when the verdict is REVIEW because the check could not be completed. */
    private String reason;

    private Instant decidedAt;
}
