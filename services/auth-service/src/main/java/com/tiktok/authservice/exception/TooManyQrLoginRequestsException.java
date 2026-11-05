package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** Too many QR codes asked for from one address in a short time. */
public class TooManyQrLoginRequestsException extends DomainException {

    public TooManyQrLoginRequestsException() {
        super("TOO_MANY_QR_LOGIN_REQUESTS", "Too many QR codes requested, try again later",
                HttpStatus.TOO_MANY_REQUESTS);
    }
}
