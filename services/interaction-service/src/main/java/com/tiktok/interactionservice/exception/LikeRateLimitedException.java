package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Too many like state changes on one video by one viewer. A repeated like is already a no-op —
 * the LWT refuses the second claim — so what this bounds is the like/unlike/like cycle, which is
 * the one sequence that produces an unbounded stream of events, realtime frames and, once
 * notifications exist, pushes to the video's owner.
 */
public class LikeRateLimitedException extends DomainException {

    public LikeRateLimitedException() {
        super("LIKE_RATE_LIMITED", "Too many like changes for this video", HttpStatus.TOO_MANY_REQUESTS);
    }
}
