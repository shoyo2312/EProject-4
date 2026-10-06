package com.tiktok.authservice.dto.response;

/**
 * Proof that the mailbox was verified, redeemed by {@code /register/complete}.
 *
 * @param expiresInMillis how long the client has to choose a password and a username
 */
public record SignupTicketResponse(
        String signupToken,
        long expiresInMillis
) {
}
