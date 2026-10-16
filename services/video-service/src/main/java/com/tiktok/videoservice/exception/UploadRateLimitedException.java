package com.tiktok.videoservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * Too many uploads started, or too many videos published, this hour or today. {@code Retry-After}
 * (whole seconds, at least one) says when the tier that refused frees up; which tier it was is not
 * said, and the message carries no numbers a translation would have to keep in step.
 */
public class UploadRateLimitedException extends DomainException {

    private final Duration retryAfter;

    public UploadRateLimitedException(Duration retryAfter) {
        super("UPLOAD_RATE_LIMITED", "You're uploading too many videos. Try again later.",
                HttpStatus.TOO_MANY_REQUESTS);
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (retryAfter.toMillis() + 999) / 1000)));
        return headers;
    }
}
