package com.tiktok.moderation;

/** What should happen to a text. Ordered from least to most severe. */
public enum Decision {
    /** Nothing found. */
    ALLOW,
    /**
     * Something a person should look at before anyone else reads it: a comment waits for the
     * video's owner, a caption sends its video to admin review.
     */
    HOLD,
    /** Refused outright. Only the worst terms in the list, never a heuristic. */
    REJECT
}
