package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class AlreadyRequestedException extends ConflictException {

    public AlreadyRequestedException() {
        super("ALREADY_REQUESTED", "A follow request to this user is already pending");
    }
}
