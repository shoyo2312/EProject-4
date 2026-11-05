package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** The inbox's follower-count filter could not be answered: user-service gave no counts. */
public class FollowerCountUnavailableException extends DomainException {

    public FollowerCountUnavailableException(Throwable cause) {
        super("FOLLOWER_COUNT_UNAVAILABLE", "Could not filter by follower count right now, please try again",
                HttpStatus.SERVICE_UNAVAILABLE);
        if (cause != null) {
            initCause(cause);
        }
    }
}
