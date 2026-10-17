package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * @param turnstileToken required only when the resolved account is an ADMIN — admin-console login
 *                       is gated on Cloudflare Turnstile. Ignored for everyone else, so the user
 *                       app keeps sending just the two credentials.
 * @param deviceToken    an earlier "remember this device" token from the admin console; when it is
 *                       still valid the email-OTP step is skipped. Null otherwise.
 * @param reactivate     true to reopen an account its owner closed. Sent after a sign-in without it
 *                       was answered ACCOUNT_DEACTIVATED or ACCOUNT_DELETION_PENDING, with the same
 *                       credential, so nothing has to be carried between the two requests.
 */
public record LoginRequest(
        @NotBlank String usernameOrEmail,
        @NotBlank String password,
        String turnstileToken,
        String deviceToken,
        Boolean reactivate
) {
    public LoginRequest(String usernameOrEmail, String password) {
        this(usernameOrEmail, password, null, null, null);
    }

    public LoginRequest(String usernameOrEmail, String password, String turnstileToken, String deviceToken) {
        this(usernameOrEmail, password, turnstileToken, deviceToken, null);
    }

    public boolean wantsReactivation() {
        return Boolean.TRUE.equals(reactivate);
    }
}
