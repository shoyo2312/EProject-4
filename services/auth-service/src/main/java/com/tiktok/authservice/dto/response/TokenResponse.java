package com.tiktok.authservice.dto.response;

/**
 * @param deviceToken set only by {@code /login/otp} when the admin ticked "remember this device";
 *                    null on every other path (plain login, refresh, social). The console stores
 *                    it in an httpOnly cookie and sends it back on the next login to skip the
 *                    email-OTP step.
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        long expiresInMillis,
        String deviceToken
) {
    public TokenResponse(String accessToken, String refreshToken, long expiresInMillis) {
        this(accessToken, refreshToken, expiresInMillis, null);
    }
}
