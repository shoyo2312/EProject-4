package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Too many watch sessions reported for one video by one viewer. Not in common-lib because no
 * other service rate-limits anything yet; move it there when a second one does.
 */
public class WatchRateLimitedException extends DomainException {

    public WatchRateLimitedException() {
        super("WATCH_RATE_LIMITED", "Too many watch sessions reported for this video", HttpStatus.TOO_MANY_REQUESTS);
    }
}
