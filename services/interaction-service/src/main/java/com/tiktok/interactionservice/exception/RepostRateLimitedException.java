package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Too many reposts recorded for one video by one viewer. Same reasoning as
 * {@link ShareRateLimitedException}: a repost moves a counter with nothing idempotent about it.
 */
public class RepostRateLimitedException extends DomainException {

    public RepostRateLimitedException() {
        super("REPOST_RATE_LIMITED", "Too many reposts recorded for this video", HttpStatus.TOO_MANY_REQUESTS);
    }
}
