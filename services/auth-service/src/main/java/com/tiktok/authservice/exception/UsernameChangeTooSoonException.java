package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

import java.time.Instant;

public class UsernameChangeTooSoonException extends DomainException {

    public UsernameChangeTooSoonException(Instant changeableAt) {
        super("USERNAME_CHANGE_TOO_SOON",
                "Username can be changed once every 30 days; next change allowed at " + changeableAt,
                HttpStatus.TOO_MANY_REQUESTS);
    }
}
