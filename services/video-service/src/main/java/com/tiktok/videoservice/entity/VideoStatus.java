package com.tiktok.videoservice.entity;

/**
 * Only PUBLISHED is on a read path. Every listing filters {@code status == PUBLISHED} rather than
 * excluding a list of hidden states, so a status added here is invisible to viewers until
 * something deliberately lets it through — which is the right default for a value that decides
 * whether content is on the platform.
 */
public enum VideoStatus {

    /** Uploaded, transcode not finished. */
    PROCESSING,

    /** Transcoded, waiting on automatic moderation. Never seen by a viewer. */
    PENDING_MODERATION,

    /** Automatic moderation was unsure, or could not run. Waiting for an admin. */
    PENDING_REVIEW,

    PUBLISHED,

    /** Transcode gave up. Terminal; see {@code Video.failureReason}. */
    FAILED,

    /**
     * Removed by automatic moderation without a human first. Distinct from TAKEN_DOWN so the two
     * can be counted and reviewed apart: one is a model's decision and the thresholds behind it
     * are still being tuned, the other is a person's and is not in question.
     */
    REJECTED,

    /** Removed by an admin. */
    TAKEN_DOWN
}
