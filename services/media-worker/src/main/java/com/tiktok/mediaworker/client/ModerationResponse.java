package com.tiktok.mediaworker.client;

/**
 * What moderation-service answers with. Field names match its JSON exactly, so there is no
 * mapping layer to keep in step with the Python side.
 *
 * @param verdict one of APPROVED, REVIEW, REJECTED
 */
public record ModerationResponse(
        String verdict,
        String label,
        double maxScore,
        int suspiciousFrames,
        int totalFrames,
        long processingTimeMs,
        String model,
        String modelVersion
) {
}
