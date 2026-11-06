package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** Too many edits of one comment by its author — 60 an hour, like every per-target budget here. */
public class CommentEditRateLimitedException extends DomainException {
    public CommentEditRateLimitedException() {
        super("COMMENT_EDIT_RATE_LIMITED", "Too many edits for this comment",
                HttpStatus.TOO_MANY_REQUESTS);
    }
}
