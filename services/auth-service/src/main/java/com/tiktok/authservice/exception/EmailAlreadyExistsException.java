package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ConflictException;

public class EmailAlreadyExistsException extends ConflictException {

    public EmailAlreadyExistsException(String email) {
        super("EMAIL_ALREADY_EXISTS", "Email already registered: " + email);
    }
}
