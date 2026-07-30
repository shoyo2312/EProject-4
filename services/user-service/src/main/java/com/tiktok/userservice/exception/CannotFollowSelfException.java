package com.tiktok.userservice.exception;

import com.tiktok.common.exception.BadRequestException;

public class CannotFollowSelfException extends BadRequestException {

    public CannotFollowSelfException() {
        super("CANNOT_FOLLOW_SELF", "A user cannot follow themselves");
    }
}
