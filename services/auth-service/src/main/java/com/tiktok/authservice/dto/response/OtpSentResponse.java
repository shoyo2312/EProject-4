package com.tiktok.authservice.dto.response;

/**
 * The answer to a call that mailed a code.
 *
 * @param resendTicket stands in for the Turnstile token on the next send to the same address for
 *                     the same purpose, for 15 minutes from the challenge that earned it. Null when
 *                     none could be issued, and the next send then needs Turnstile again.
 */
public record OtpSentResponse(
        String resendTicket
) {
}
