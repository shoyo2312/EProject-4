package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * A 429 that says when trying again can work, in {@code Retry-After} (whole seconds, at least one):
 * a client can disable the send button for exactly that long instead of guessing, and the message
 * stays free of numbers a translation would have to carry.
 */
public abstract class RetryLaterException extends DomainException {

    private final Duration retryAfter;

    protected RetryLaterException(String code, String message, Duration retryAfter) {
        super(code, message, HttpStatus.TOO_MANY_REQUESTS);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(seconds));
        return headers;
    }
}
