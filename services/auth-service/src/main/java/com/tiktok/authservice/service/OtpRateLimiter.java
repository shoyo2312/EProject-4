package com.tiktok.authservice.service;

import com.tiktok.authservice.exception.TooManyOtpRequestsException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Redis-backed request throttle for resend-verification / forgot-password, keyed by
 * purpose+email so a single account can't be used to spam the mail provider. Unlike
 * LoginRateLimiter, this must survive across auth-service replicas, hence Redis rather
 * than an in-memory map.
 */
@Component
@RequiredArgsConstructor
public class OtpRateLimiter {

    private static final String KEY_PREFIX = "auth:otp-rate:";
    private static final int MAX_REQUESTS = 3;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public void checkAllowed(String purpose, String email) {
        String key = KEY_PREFIX + purpose + ":" + email.toLowerCase(Locale.ROOT);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
        if (count != null && count > MAX_REQUESTS) {
            throw new TooManyOtpRequestsException();
        }
    }
}
