package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * @param token what the provider's SDK handed the client: Google's ID token (the {@code
 *              credential} on web, {@code idToken} on Flutter) or Facebook's access token. Which of
 *              the two it is follows from the endpoint it was posted to.
 */
public record SocialLoginRequest(
        @NotBlank String token
) {
}
