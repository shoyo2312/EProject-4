package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class TooManyLoginAttemptsException extends DomainException {

    public TooManyLoginAttemptsException() {
        super("TOO_MANY_LOGIN_ATTEMPTS", "Too many failed login attempts, try again later", HttpStatus.TOO_MANY_REQUESTS);
    }
}
