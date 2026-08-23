package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Too many comments posted on one video by one viewer. Unlike a like, a comment has nothing
 * idempotent about it — every call is a new row, a new counter increment and a new event that
 * moves the video up trending — so this limit is what stands between that ranking and a loop.
 */
public class CommentRateLimitedException extends DomainException {

    public CommentRateLimitedException() {
        super("COMMENT_RATE_LIMITED", "Too many comments posted on this video", HttpStatus.TOO_MANY_REQUESTS);
    }
}
