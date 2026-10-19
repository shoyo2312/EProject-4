package com.tiktok.interactionservice.exception;

import java.time.Duration;

/**
 * Too many comments from one account — too fast, too many this minute, hour or day, or too many on
 * one video. Unlike a like, a comment has nothing idempotent about it: every call is a new row, a
 * new counter increment and a new event that moves the video up trending, so these limits are what
 * stand between that ranking and a loop. Which tier refused is not said; {@code Retry-After} says
 * when the refusal ends.
 */
public class CommentRateLimitedException extends RetryLaterException {

    public CommentRateLimitedException(Duration retryAfter) {
        super("COMMENT_RATE_LIMITED", "You're commenting too fast. Try again in a moment.", retryAfter);
    }
}
