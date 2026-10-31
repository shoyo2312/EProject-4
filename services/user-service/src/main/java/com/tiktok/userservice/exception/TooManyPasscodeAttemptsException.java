package com.tiktok.userservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * Five wrong daily-limit passcodes in a row: checking is locked for a while. {@code Retry-After}
 * carries how long, in whole seconds, so the web can say so without parsing the message.
 */
public class TooManyPasscodeAttemptsException extends DomainException {

    private final Duration retryAfter;

    public TooManyPasscodeAttemptsException(Duration retryAfter) {
        super("TOO_MANY_PASSCODE_ATTEMPTS", "Too many wrong passcodes. Try again later.", HttpStatus.TOO_MANY_REQUESTS);
        this.retryAfter = retryAfter;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (retryAfter.toMillis() + 999) / 1000)));
        return headers;
    }
}
