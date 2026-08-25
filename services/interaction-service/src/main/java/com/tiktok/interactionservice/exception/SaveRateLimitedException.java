package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Too many save/unsave calls for one video by one user. A save moves no public counter, so this
 * is not about the ranking the way {@link ShareRateLimitedException} is: each call is an LWT, and
 * a client stuck toggling a bookmark button is the cheapest way to spend the cluster's Paxos
 * budget.
 */
public class SaveRateLimitedException extends DomainException {

    public SaveRateLimitedException() {
        super("SAVE_RATE_LIMITED", "Too many save changes for this video", HttpStatus.TOO_MANY_REQUESTS);
    }
}
