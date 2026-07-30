package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ConflictException;

public class UsernameAlreadyExistsException extends ConflictException {

    public UsernameAlreadyExistsException(String username) {
        super("USERNAME_ALREADY_EXISTS", "Username already taken: " + username);
    }
}
