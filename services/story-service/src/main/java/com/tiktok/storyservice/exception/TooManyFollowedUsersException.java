package com.tiktok.storyservice.exception;

import com.tiktok.common.exception.BadRequestException;

/** Same cap and code as video-service's Following feed. */
public class TooManyFollowedUsersException extends BadRequestException {

    public TooManyFollowedUsersException(int max) {
        super("TOO_MANY_FOLLOWED_USERS", "A story feed request may name at most %d accounts".formatted(max));
    }
}
