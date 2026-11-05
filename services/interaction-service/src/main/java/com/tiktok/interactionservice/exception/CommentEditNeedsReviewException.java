package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The edited text would have been held for review had it been posted new. An edit is refused
 * instead: holding a comment that is already up would take it, its replies and its count out of the
 * thread, and letting it through would make editing a way around the filters. Nothing is changed.
 */
public class CommentEditNeedsReviewException extends DomainException {
    public CommentEditNeedsReviewException() {
        super("COMMENT_EDIT_NEEDS_REVIEW", "This edit needs review and was not saved.",
                HttpStatus.UNPROCESSABLE_CONTENT);
    }
}
