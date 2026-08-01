package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class AlreadyMutedException extends ConflictException {

    public AlreadyMutedException() {
        super("ALREADY_MUTED", "Already muting this user");
    }
}
