package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

public class TurnstileVerificationException extends DomainException {

    public TurnstileVerificationException() {
        super("TURNSTILE_VERIFICATION_FAILED", "Turnstile verification required or failed", HttpStatus.FORBIDDEN);
    }
}
