package com.tiktok.authservice.exception;

import com.tiktok.common.exception.UnauthorizedException;

public class InvalidCredentialsException extends UnauthorizedException {

    public InvalidCredentialsException() {
        super("INVALID_CREDENTIALS", "Invalid username/email or password");
    }
}
