package com.tiktok.authservice.service;

import com.tiktok.authservice.exception.TooManyOtpRequestsException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;

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
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpRateLimiter {

    private static final String SEND_KEY_PREFIX = "auth:otp-rate:";
    private static final String GUESS_KEY_PREFIX = "auth:otp-fail:";
    private static final int MAX_REQUESTS = 3;
    private static final int MAX_GUESSES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;

    /**
     * Fails open: an unreachable Redis lets the mail go out unthrottled. The cost is mail volume,
     * which is recoverable; refusing every verification and password-reset mail because a cache is
     * down is not.
     */
    public void checkAllowed(String purpose, String email) {
        String key = sendKey(purpose, email);
        Long count = failOpen(() -> redisTemplate.opsForValue().increment(key), "count OTP sends");
        if (count != null && count == 1L) {
            failOpen(() -> redisTemplate.expire(key, WINDOW), "set OTP send window");
        }
        if (count != null && count > MAX_REQUESTS) {
            throw new TooManyOtpRequestsException();
        }
    }

    /**
     * Fails <em>closed</em> — the one place in this class that does. This counter is all that
     * stands between a distributed attacker and a 1e6-value secret; treating an unreachable Redis
     * as "no failures recorded" would hand out unlimited guesses for the length of the outage, and
     * an OTP guessed that way is a stolen account. Denying instead costs users the ability to
     * verify an email or finish a password reset until Redis is back — a delay, not a compromise.
     *
     * <p>Deliberately unlike {@link #checkAllowed} above and {@link LoginRateLimiter}: losing those
     * counters costs mail volume and brute-force protection that a password still backs, while
     * losing this one costs the whole secret.
     */
    public void checkGuessAllowed(String purpose, String email) {
        String value;
        try {
            value = redisTemplate.opsForValue().get(guessKey(purpose, email));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable for OTP guess limit, failing closed", e);
            throw new TooManyOtpRequestsException();
        }
        if (value != null && Integer.parseInt(value) >= MAX_GUESSES) {
            throw new TooManyOtpRequestsException();
        }
    }

    public void recordGuessFailure(String purpose, String email) {
        String key = guessKey(purpose, email);
        Long count = failOpen(() -> redisTemplate.opsForValue().increment(key), "record OTP guess failure");
        if (count != null && count == 1L) {
            failOpen(() -> redisTemplate.expire(key, WINDOW), "set OTP guess window");
        }
    }

    public void recordGuessSuccess(String purpose, String email) {
        failOpen(() -> redisTemplate.delete(guessKey(purpose, email)), "clear OTP guess failures");
    }

    /**
     * The send counter and the bookkeeping writes swallow Redis failures rather than let them reach
     * the catch-all handler as a 500; see each method for which way its limit leans. What is
     * wrapped is the Redis call, never the limit check — TooManyOtpRequestsException is a
     * RuntimeException too.
     */
    private <T> T failOpen(Supplier<T> operation, String action) {
        try {
            return operation.get();
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, failing open: {}", action, e);
            return null;
        }
    }

    private String sendKey(String purpose, String email) {
        return SEND_KEY_PREFIX + purpose + ":" + email.toLowerCase(Locale.ROOT);
    }

    private String guessKey(String purpose, String email) {
        return GUESS_KEY_PREFIX + purpose + ":" + email.toLowerCase(Locale.ROOT);
    }
}
