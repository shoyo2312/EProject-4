package com.tiktok.userservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class NotMutedException extends ResourceNotFoundException {

    public NotMutedException() {
        super("NOT_MUTED", "Not currently muting this user");
    }
}
