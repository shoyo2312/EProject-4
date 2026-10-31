package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** Same cap as user-service's batch endpoints put on ids in one request. */
public class TooManyRingUsersException extends BadRequestException {

    public TooManyRingUsersException(int max) {
        super("TOO_MANY_RING_USERS", "A story rings request may name at most %d accounts".formatted(max));
    }
}
