package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Too many plays counted for one video by one viewer. Separate code from
 * {@link WatchRateLimitedException} because the two limits are hit by different clients doing
 * different things, and a client that sees this one is being told its view was not counted.
 */
public class ViewRateLimitedException extends DomainException {

    public ViewRateLimitedException() {
        super("VIEW_RATE_LIMITED", "Too many views recorded for this video", HttpStatus.TOO_MANY_REQUESTS);
    }
}
