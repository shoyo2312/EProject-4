package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * @param token the same provider token the rejected login used. Re-sent rather than remembered
 *              server-side: a challenge table would be one more thing to expire and clean up, and
 *              the token is re-verified here anyway, which is the stronger check.
 * @param otp   the six digits mailed to the address behind that token.
 */
public record SocialLinkRequest(
        @NotBlank String token,
        @NotBlank @Pattern(regexp = "\\d{6}") String otp
) {
}
