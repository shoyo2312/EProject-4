package com.tiktok.userservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

import java.time.Instant;

public class DisplayNameChangeTooSoonException extends DomainException {

    public DisplayNameChangeTooSoonException(Instant changeableAt) {
        super("DISPLAY_NAME_CHANGE_TOO_SOON",
                "Display name can be changed once every 7 days; next change allowed at " + changeableAt,
                HttpStatus.TOO_MANY_REQUESTS);
    }
}
