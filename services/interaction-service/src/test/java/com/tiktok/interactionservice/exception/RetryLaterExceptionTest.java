package com.tiktok.interactionservice.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryLaterExceptionTest {

    @Test
    void retryAfter_isWholeSecondsRoundedUp() {
        assertThat(new CommentRateLimitedException(Duration.ofMillis(2_100)).getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("3");
    }

    /** "Retry-After: 0" reads as "now", which is exactly when trying again fails. */
    @Test
    void retryAfter_isNeverZero() {
        assertThat(new DuplicateCommentException(Duration.ZERO).getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("1");
    }

    @Test
    void bothAre429_withTheirOwnCodes() {
        assertThat(new CommentRateLimitedException(Duration.ofSeconds(1)).getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(new CommentRateLimitedException(Duration.ofSeconds(1)).getCode()).isEqualTo("COMMENT_RATE_LIMITED");
        assertThat(new DuplicateCommentException(Duration.ofSeconds(1)).getCode()).isEqualTo("DUPLICATE_COMMENT");
    }
}
