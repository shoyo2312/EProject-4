package com.tiktok.storyservice.entity;

/**
 * Where a story is in moderation. Every viewer-facing read whitelists {@link #ACTIVE}, so a status
 * added later is invisible to viewers by default; the owner sees all of their own.
 */
public enum StoryStatus {
    /** Posted, media not screened yet. */
    PENDING_MODERATION,
    /** Approved; visible to its audience until it expires. */
    ACTIVE,
    /** The model was unsure or could not run. Hidden until it expires — no admin queue for stories. */
    HELD,
    REJECTED
}
