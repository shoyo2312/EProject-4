package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/** The QR code timed out, was already used, or never existed. The phone asks for a new one. */
public class QrLoginExpiredException extends DomainException {

    public QrLoginExpiredException() {
        super("QR_LOGIN_EXPIRED", "This QR code has expired, refresh it and scan again", HttpStatus.GONE);
    }
}
