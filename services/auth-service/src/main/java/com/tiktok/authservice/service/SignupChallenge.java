package com.tiktok.authservice.service;

import com.tiktok.crypto.hash.HashUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The state of a signup that has proven its mailbox but not yet become an account — the
 * email-first flow ({@code /register/start} → {@code /register/verify} →
 * {@code /register/complete}).
 *
 * <p>In Redis rather than {@code verification_tokens}: that table hangs every code off a
 * {@code user_id}, and the whole point of this flow is that no user row exists until the code has
 * been spent. Both halves expire on their own, so there is nothing to clean up.
 *
 * <p>Only hashes are stored, as CLAUDE.md requires of anything secret in Redis: the code is hashed
 * with the address it was sent to, and the ticket is stored under its own hash. The password never
 * passes through here at all — it arrives with {@code /register/complete} and goes straight into
 * the user row.
 */
@Component
@RequiredArgsConstructor
public class SignupChallenge {

    private static final String CODE_KEY_PREFIX = "auth:signup:code:";
    private static final String TICKET_KEY_PREFIX = "auth:signup:ticket:";

    /**
     * How long a proven mailbox stays good for. Long enough to choose a password and a username
     * without hurrying, short enough that a ticket left in a closed tab is not a standing way in.
     */
    static final Duration TICKET_TTL = Duration.ofMinutes(30);

    /**
     * Compare and delete in one step, so the same code submitted twice at once is spent once. A
     * GET followed by a DEL lets both submissions read the code before either removes it.
     */
    private static final RedisScript<Long> SPEND_IF_MATCHES = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom random = new SecureRandom();

    /** Replaces whatever code the address last held, so a resend kills the previous one. */
    public void storeCode(String email, String otp, Duration ttl) {
        redisTemplate.opsForValue().set(codeKey(email), codeHash(email, otp), ttl);
    }

    /** True exactly once for the right code; false for a wrong, expired or already-spent one. */
    public boolean spendCode(String email, String otp) {
        Long deleted = redisTemplate.execute(SPEND_IF_MATCHES, List.of(codeKey(email)), codeHash(email, otp));
        return deleted != null && deleted > 0;
    }

    /** The ticket {@code /register/complete} redeems. Returned raw once, stored only as a hash. */
    public String issueTicket(String email) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        redisTemplate.opsForValue().set(ticketKey(ticket), normalise(email), TICKET_TTL);
        return ticket;
    }

    /**
     * The address a ticket proved, without spending it: a username that turns out to be taken
     * must leave the ticket usable for the next attempt.
     */
    public Optional<String> emailFor(String ticket) {
        return Optional.ofNullable(redisTemplate.opsForValue().get(ticketKey(ticket)));
    }

    /** Spends the ticket; false when another request already did. */
    public boolean spendTicket(String ticket) {
        return Boolean.TRUE.equals(redisTemplate.delete(ticketKey(ticket)));
    }

    private String codeKey(String email) {
        return CODE_KEY_PREFIX + normalise(email);
    }

    private String ticketKey(String ticket) {
        return TICKET_KEY_PREFIX + HashUtils.sha256(ticket);
    }

    /** Salted with the address, so a code cannot be replayed against another pending signup. */
    private String codeHash(String email, String otp) {
        return HashUtils.sha256(normalise(email) + ":signup:" + otp);
    }

    private static String normalise(String email) {
        return email.toLowerCase(Locale.ROOT);
    }
}
