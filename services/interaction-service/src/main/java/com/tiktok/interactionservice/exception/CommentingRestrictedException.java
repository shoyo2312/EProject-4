package com.tiktok.interactionservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/**
 * The account broke the comment rules too often lately — comments refused for what they said, or
 * removed by an admin — and may not comment until the restriction ends. 403 rather than 429: it is a
 * sanction, not a pace. {@code Retry-After} (whole seconds, at least one) says when it ends, so the
 * client can say "until 21:30" without a second call.
 */
public class CommentingRestrictedException extends DomainException {

    private final Duration remaining;

    public CommentingRestrictedException(Duration remaining) {
        super("COMMENTING_RESTRICTED",
                "You can't comment for a while because of repeated violations of our community guidelines.",
                HttpStatus.FORBIDDEN);
        this.remaining = remaining;
    }

    public Duration getRemaining() {
        return remaining;
    }

    @Override
    public HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, (remaining.toMillis() + 999) / 1000)));
        return headers;
    }
}
