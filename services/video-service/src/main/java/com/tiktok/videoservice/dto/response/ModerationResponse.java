package com.tiktok.videoservice.dto.response;

import com.tiktok.event.video.ModerationVerdict;

import java.time.Instant;

/**
 * What automatic moderation said, for the admin console only.
 *
 * <p>A video the classifier removed carries no {@code takedownReason} — that field is written by
 * an admin taking a video down by hand — so without these numbers the console shows a removal with
 * no explanation, at exactly the moment someone needs one to judge the call.
 *
 * <p>Kept off the public read paths: the score is the distance to the threshold, and an uploader
 * who can watch it move can binary-search their way to just under it.
 */
public record ModerationResponse(
        ModerationVerdict verdict,
        String label,
        double maxScore,
        int suspiciousFrames,
        int totalFrames,
        String model,
        String modelVersion,
        /** Why no verdict could be reached; null unless the check itself failed. */
        String reason,
        Instant checkedAt
) {
}
