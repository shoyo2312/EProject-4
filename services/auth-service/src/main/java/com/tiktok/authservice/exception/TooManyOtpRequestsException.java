package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class TooManyOtpRequestsException extends DomainException {

    public TooManyOtpRequestsException() {
        super("TOO_MANY_OTP_REQUESTS", "Too many requests, try again later", HttpStatus.TOO_MANY_REQUESTS);
    }
}
