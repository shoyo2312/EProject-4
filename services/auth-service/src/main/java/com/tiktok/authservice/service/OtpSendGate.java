package com.tiktok.authservice.service;

import com.tiktok.authservice.exception.TurnstileVerificationException;
import com.tiktok.crypto.hash.HashUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;

/**
 * The check in front of the calls that mail a code: a Cloudflare Turnstile token, or the resend
 * ticket that an earlier send to the same address handed back.
 *
 * <p>Turnstile is there to stop a script mailing codes to one address after another, which the
 * per-address budget in {@link OtpRateLimiter} cannot do: every new address starts with a full
 * budget. A resend is the other case, the same address from a client that has just passed
 * Turnstile for it. A second challenge there adds nothing the budget does not already enforce,
 * and only makes the person waiting for the code solve it again. The ticket is how the server
 * remembers that pass:
 * <ul>
 *   <li>It is bound to one purpose and one address, so it cannot mail any other address, which
 *       is what Turnstile guards.</li>
 *   <li>It lives {@link #TICKET_TTL} from the challenge that earned it, and using it does not
 *       extend that. Otherwise one solved challenge would go on mailing an address every time the
 *       budget refilled.</li>
 *   <li>It is held by the client that solved the challenge. A pass remembered under the address
 *       alone would let anyone resend to an address that someone is in the middle of verifying,
 *       and every send kills the code before it, the one that person is about to type.</li>
 * </ul>
 *
 * <p>Only the ticket's hash is stored, as CLAUDE.md requires of anything secret in Redis. Trouble
 * with Redis falls back on Turnstile and never past it: a ticket that cannot be checked counts as
 * absent, and one that cannot be stored is not handed out.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OtpSendGate {

    private static final String TICKET_KEY_PREFIX = "auth:otp-resend:";

    /** The send budget's window, so within a ticket's life it is the budget that bounds a client. */
    static final Duration TICKET_TTL = Duration.ofMinutes(15);

    private final TurnstileService turnstileService;
    private final StringRedisTemplate redisTemplate;
    private final SecureRandom random = new SecureRandom();

    /**
     * Lets one send through, or throws {@link TurnstileVerificationException}. Answers the ticket
     * the response should carry: the one presented, when it covers this send; otherwise a new one
     * once the Turnstile token verifies, or null if Redis could not store it.
     *
     * <p>Called first, before any other check, the way Turnstile always has been. A send refused
     * further on (409, 429) therefore leaves a new ticket that nobody was given, and it expires
     * unused.
     */
    public String admit(String purpose, String email, String turnstileToken, String resendTicket) {
        String holder = holder(purpose, email);
        if (resendTicket != null && !resendTicket.isBlank() && holder.equals(holderOf(resendTicket))) {
            return resendTicket;
        }
        turnstileService.verify(turnstileToken);
        return issue(holder);
    }

    private String holderOf(String ticket) {
        try {
            return redisTemplate.opsForValue().get(ticketKey(ticket));
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, treating the resend ticket as absent", e);
            return null;
        }
    }

    private String issue(String holder) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            redisTemplate.opsForValue().set(ticketKey(ticket), holder, TICKET_TTL);
            return ticket;
        } catch (RuntimeException e) {
            log.warn("Redis unavailable, sending without a resend ticket", e);
            return null;
        }
    }

    private static String holder(String purpose, String email) {
        return purpose + ":" + email.toLowerCase(Locale.ROOT);
    }

    private static String ticketKey(String ticket) {
        return TICKET_KEY_PREFIX + HashUtils.sha256(ticket);
    }
}
