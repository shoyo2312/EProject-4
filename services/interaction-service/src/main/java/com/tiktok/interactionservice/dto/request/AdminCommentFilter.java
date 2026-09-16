package com.tiktok.interactionservice.dto.request;

/**
 * Which slice of a video's comments the moderation console is asking for.
 *
 * <p>Server-side because the console pages: filtering the page it happens to hold would mean
 * "removed among the twenty just loaded", which reads as "removed in this thread" and is not.
 */
public enum AdminCommentFilter {

    /** Top-level comments only, newest first. Replies come from the per-comment replies endpoint. */
    THREAD,

    /** Every reply on the video, whichever comment it hangs under, newest first. */
    REPLIES,

    /** Everything removed on the video, top-level and replies alike, newest first. */
    REMOVED
}
