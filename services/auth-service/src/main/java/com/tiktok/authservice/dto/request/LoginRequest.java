package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * @param turnstileToken required only when the resolved account is an ADMIN — admin-console login
 *                       is gated on Cloudflare Turnstile. Ignored for everyone else, so the user
 *                       app keeps sending just the two credentials.
 * @param deviceToken    an earlier "remember this device" token from the admin console; when it is
 *                       still valid the email-OTP step is skipped. Null otherwise.
 */
public record LoginRequest(
        @NotBlank String usernameOrEmail,
        @NotBlank String password,
        String turnstileToken,
        String deviceToken
) {
    public LoginRequest(String usernameOrEmail, String password) {
        this(usernameOrEmail, password, null, null);
    }
}
