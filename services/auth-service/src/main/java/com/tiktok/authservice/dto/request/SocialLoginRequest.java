package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * @param token      what the provider's SDK handed the client: Google's ID token (the {@code
 *                   credential} on web, {@code idToken} on Flutter) or Facebook's access token.
 *                   Which of the two it is follows from the endpoint it was posted to.
 * @param reactivate true to reopen an account its owner closed — see {@link LoginRequest}.
 */
public record SocialLoginRequest(
        @NotBlank String token,
        Boolean reactivate
) {
    public SocialLoginRequest(String token) {
        this(token, null);
    }

    public boolean wantsReactivation() {
        return Boolean.TRUE.equals(reactivate);
    }
}
