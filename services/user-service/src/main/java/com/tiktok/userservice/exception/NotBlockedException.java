package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class NotBlockedException extends ResourceNotFoundException {

    public NotBlockedException() {
        super("NOT_BLOCKED", "Not currently blocking this user");
    }
}
