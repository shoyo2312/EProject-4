package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ResourceNotFoundException;

public class UserNotFoundException extends ResourceNotFoundException {

    public UserNotFoundException(String identifier) {
        super("USER_NOT_FOUND", "User not found: " + identifier);
    }
}
