package com.tiktok.authservice.service;

import com.tiktok.authservice.exception.TooManyLoginAttemptsException;
import com.tiktok.authservice.exception.TooManyOtpRequestsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What each limiter does when Redis is unreachable. All of this used to let a
 * RedisConnectionFailureException escape to the catch-all handler, so a Redis outage turned every
 * login, verification and password reset into a 500 — the whole authentication surface down
 * because a cache was.
 */
class RateLimiterRedisOutageTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    private final LoginRateLimiter loginRateLimiter = new LoginRateLimiter(redisTemplate);
    private final OtpRateLimiter otpRateLimiter = new OtpRateLimiter(redisTemplate);

    @BeforeEach
    void redisIsDown() {
        RedisConnectionFailureException down = new RedisConnectionFailureException("connection refused");
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenThrow(down);
        when(redisTemplate.delete(anyString())).thenThrow(down);
    }

    @Test
    void login_survivesRedisOutage() {
        assertThatCode(() -> loginRateLimiter.acquireAttempt("bob@example.com"))
                .as("a login must not fail because the throttle counter is unreachable")
                .doesNotThrowAnyException();
        assertThatCode(() -> loginRateLimiter.recordSuccess("bob@example.com")).doesNotThrowAnyException();
    }

    @Test
    void otpSend_survivesRedisOutage() {
        assertThatCode(() -> otpRateLimiter.checkAllowed("email-verification", "bob@example.com"))
                .doesNotThrowAnyException();
        assertThatCode(() -> otpRateLimiter.recordGuessSuccess("email-verification", "bob@example.com"))
                .doesNotThrowAnyException();
    }

    @Test
    void otpGuess_failsClosedDuringRedisOutage() {
        assertThatThrownBy(() -> otpRateLimiter.acquireGuess("email-verification", "bob@example.com"))
                .as("with no counter there is no limit on guesses, and the OTP is only 1e6 values")
                .isInstanceOf(TooManyOtpRequestsException.class);
    }

    @Test
    void loginLimit_stillAppliesWhenRedisAnswers() {
        StringRedisTemplate healthy = mock(StringRedisTemplate.class);
        when(healthy.execute(any(RedisScript.class), anyList(), any())).thenReturn(6L);

        assertThatThrownBy(() -> new LoginRateLimiter(healthy).acquireAttempt("bob@example.com"))
                .as("failing open must not swallow the limit exception itself — it is a RuntimeException too")
                .isInstanceOf(TooManyLoginAttemptsException.class);
    }
}
