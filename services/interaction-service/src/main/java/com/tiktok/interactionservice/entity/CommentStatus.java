package com.tiktok.interactionservice.entity;

/**
 * Whether a comment is out where viewers can read it. Stored as text on {@code comments_by_video};
 * null on every row written before this existed, which reads as {@link #VISIBLE}.
 */
public enum CommentStatus {

    /** Listed, counted and announced — every comment before text moderation. */
    VISIBLE,

    /**
     * Stored, but waiting for the video's owner: the text rules found something a person should
     * look at first. Not in {@code comment_index}, not counted, no event — until approved, it does
     * not exist for anyone but its author (who was told) and the owner (who reviews it).
     */
    HELD
}
