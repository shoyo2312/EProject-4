package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * The reset ticket is unknown, expired, or was already redeemed. Not distinguished: the client
 * does the same thing in every case — ask for a new code.
 */
public class InvalidResetTokenException extends DomainException {

    public InvalidResetTokenException() {
        super("RESET_SESSION_EXPIRED", "Password reset session expired or already used", HttpStatus.BAD_REQUEST);
    }
}
