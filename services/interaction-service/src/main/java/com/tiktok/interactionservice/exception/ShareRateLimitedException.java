package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Too many shares recorded for one video by one viewer. Sharing is the highest-weighted signal
 * trending has, so an unbounded endpoint is the cheapest way to push a video up the ranking.
 */
public class ShareRateLimitedException extends DomainException {

    public ShareRateLimitedException() {
        super("SHARE_RATE_LIMITED", "Too many shares recorded for this video", HttpStatus.TOO_MANY_REQUESTS);
    }
}
