package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ForbiddenException;

public class CannotFollowBlockedUserException extends ForbiddenException {

    public CannotFollowBlockedUserException() {
        super("USER_BLOCKED", "Cannot follow this user because of an existing block");
    }
}
