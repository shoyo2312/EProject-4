package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The signup ticket is unknown, expired, or was already redeemed. Not distinguished: the client
 * does the same thing in every case — start the signup again from the email.
 */
public class InvalidSignupTokenException extends DomainException {

    public InvalidSignupTokenException() {
        super("SIGNUP_SESSION_EXPIRED", "Signup session expired or already used", HttpStatus.BAD_REQUEST);
    }
}
