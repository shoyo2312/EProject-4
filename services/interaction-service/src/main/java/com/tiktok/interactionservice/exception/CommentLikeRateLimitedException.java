package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Too many like state changes on one comment by one viewer. Same bound as
 * {@link LikeRateLimitedException} and for the same reason: a repeated like is already a no-op,
 * so what is unbounded is the like/unlike cycle, which produces an event, a counter write and a
 * realtime frame every time round.
 */
public class CommentLikeRateLimitedException extends DomainException {

    public CommentLikeRateLimitedException() {
        super("COMMENT_LIKE_RATE_LIMITED", "Too many like changes for this comment",
                HttpStatus.TOO_MANY_REQUESTS);
    }
}
