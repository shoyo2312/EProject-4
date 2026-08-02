package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class AlreadyBlockedException extends ConflictException {

    public AlreadyBlockedException() {
        super("ALREADY_BLOCKED", "Already blocking this user");
    }
}
