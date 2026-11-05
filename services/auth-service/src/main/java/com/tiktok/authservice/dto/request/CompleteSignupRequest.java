package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Last step of the email-first signup. The address is not in here: it is whatever the ticket
 * proved, so it cannot be swapped for one that was never verified.
 *
 * @param birthDate ISO date; the server repeats the client's age gate (13+, not in the future,
 *                  not before 1900) and stores it on the profile through the registration event
 */
public record CompleteSignupRequest(
        @NotBlank String signupToken,
        @NotBlank @Pattern(regexp = UsernameRule.REGEXP, message = UsernameRule.MESSAGE) String username,
        @NotBlank @Size(min = 8, max = 100) @FitsBcrypt String password,
        @NotNull LocalDate birthDate
) {
}
