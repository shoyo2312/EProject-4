package com.tiktok.event.video;

/**
 * What automatic moderation decided about a video's frames.
 *
 * <p>Three values rather than two, because a classifier that is only allowed to say yes or no has
 * to put its uncertainty somewhere, and both places are wrong: pass the borderline ones and the
 * platform ships what it meant to catch, block them and it removes ordinary videos with no human
 * ever looking. REVIEW is where the uncertainty goes.
 */
public enum ModerationVerdict {

    /** Nothing found. The video goes live. */
    APPROVED,

    /** Not confident either way, or the check could not be completed. A human decides. */
    REVIEW,

    /** Confident enough to remove without a human first. Still reversible by an admin. */
    REJECTED
}
