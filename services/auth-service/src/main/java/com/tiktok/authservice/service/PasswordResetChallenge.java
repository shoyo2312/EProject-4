package com.tiktok.authservice.service;

import com.tiktok.crypto.hash.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;

/**
 * The ticket between the two halves of a password reset in the modal flow
 * ({@code /reset-password/verify} → {@code /reset-password/complete}): proof that the code was
 * right, so the client can say so before asking for a new password rather than after.
 *
 * <p>The code itself stays in {@code verification_tokens} and is spent by {@link OtpService} like
 * any other; only the ticket lives here, stored under its own hash, as CLAUDE.md requires of
 * anything secret in Redis.
 */
@Component
@RequiredArgsConstructor
public class PasswordResetChallenge {

    private static final String TICKET_KEY_PREFIX = "auth:password-reset:ticket:";

    /** As long as the code it replaces lived: holding the ticket must not outlast holding the code. */
    static final Duration TICKET_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom random = new SecureRandom();

    /** Returned raw once, stored only as a hash. */
    public String issueTicket(Long userId) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(ticketKey(ticket), String.valueOf(userId), TICKET_TTL);
        return ticket;
    }

    /**
     * The account the ticket was issued for, spending it in the same step: GETDEL, so two
     * submissions of one ticket cannot both set a password.
     */
    public Optional<Long> spendTicket(String ticket) {
        return Optional.ofNullable(redisTemplate.opsForValue().getAndDelete(ticketKey(ticket)))
                .map(Long::valueOf);
    }

    private String ticketKey(String ticket) {
        return TICKET_KEY_PREFIX + HashUtils.sha256(ticket);
    }
}
