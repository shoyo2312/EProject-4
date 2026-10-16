package com.tiktok.interactionservice.exception;

import java.time.Duration;

/**
 * The same text again: on the same video, or on more videos than a person pastes one comment onto.
 * Matched on the text as the rules read it, so "Hay quá!!!" and "hay qua" are the same comment.
 */
public class DuplicateCommentException extends RetryLaterException {

    public DuplicateCommentException(Duration retryAfter) {
        super("DUPLICATE_COMMENT", "You already posted this comment.", retryAfter);
    }
}
