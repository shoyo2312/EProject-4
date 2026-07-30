package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ConflictException;

public class AlreadyFollowingException extends ConflictException {

    public AlreadyFollowingException() {
        super("ALREADY_FOLLOWING", "Already following this user");
    }
}
