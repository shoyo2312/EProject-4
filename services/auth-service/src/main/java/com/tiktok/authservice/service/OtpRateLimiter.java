package com.tiktok.authservice.service;

import com.tiktok.authservice.exception.TooManyOtpRequestsException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * Redis-backed throttles for the OTP flows, keyed by purpose+email so the limits hold across
 * auth-service replicas.
 *
 * <p>Two independent counters, defending against different attacks:
 * <ul>
 *   <li><b>send</b> ({@link #checkAllowed}) — caps how many OTPs get mailed, so an account
 *       can't be used to spam the mail provider.</li>
 *   <li><b>guess</b> ({@link #checkGuessAllowed}) — caps wrong submissions at verify-email and
 *       reset-password. A 6-digit OTP is only 1e6 values and lives 15 minutes; without this a
 *       distributed attacker simply enumerates it, since the gateway's per-IP limit doesn't
 *       bind when attempts arrive from many IPs.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class OtpRateLimiter {

    private static final String SEND_KEY_PREFIX = "auth:otp-rate:";
    private static final String GUESS_KEY_PREFIX = "auth:otp-fail:";
    private static final int MAX_REQUESTS = 3;
    private static final int MAX_GUESSES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    public void checkAllowed(String purpose, String email) {
        String key = sendKey(purpose, email);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
        if (count != null && count > MAX_REQUESTS) {
            throw new TooManyOtpRequestsException();
        }
    }

    public void checkGuessAllowed(String purpose, String email) {
        String value = redisTemplate.opsForValue().get(guessKey(purpose, email));
        if (value != null && Integer.parseInt(value) >= MAX_GUESSES) {
            throw new TooManyOtpRequestsException();
        }
    }

    public void recordGuessFailure(String purpose, String email) {
        String key = guessKey(purpose, email);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, WINDOW);
        }
    }

    public void recordGuessSuccess(String purpose, String email) {
        redisTemplate.delete(guessKey(purpose, email));
    }

    private String sendKey(String purpose, String email) {
        return SEND_KEY_PREFIX + purpose + ":" + email.toLowerCase(Locale.ROOT);
    }

    private String guessKey(String purpose, String email) {
        return GUESS_KEY_PREFIX + purpose + ":" + email.toLowerCase(Locale.ROOT);
    }
}
