package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Last step of the email-first signup. The address is not in here: it is whatever the ticket
 * proved, so it cannot be swapped for one that was never verified.
 */
public record CompleteSignupRequest(
        @NotBlank String signupToken,
        @NotBlank @Pattern(regexp = UsernameRule.REGEXP, message = UsernameRule.MESSAGE) String username,
        @NotBlank @Size(min = 8, max = 100) String password
) {
}
