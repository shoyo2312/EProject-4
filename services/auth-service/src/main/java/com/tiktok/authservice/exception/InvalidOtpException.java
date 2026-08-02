package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class InvalidOtpException extends DomainException {

    public InvalidOtpException() {
        super("INVALID_OTP", "Invalid or expired verification code", HttpStatus.BAD_REQUEST);
    }
}
