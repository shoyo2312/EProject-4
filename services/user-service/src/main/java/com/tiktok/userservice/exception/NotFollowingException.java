package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class NotFollowingException extends ResourceNotFoundException {

    public NotFollowingException() {
        super("NOT_FOLLOWING", "Not currently following this user");
    }
}
