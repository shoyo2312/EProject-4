package com.tiktok.authservice.dto.response;

/**
 * Proof that the reset code was right, redeemed by {@code /reset-password/complete}.
 *
 * @param expiresInMillis how long the client has to choose the new password
 */
public record PasswordResetTicketResponse(
        String resetToken,
        long expiresInMillis
) {
}
