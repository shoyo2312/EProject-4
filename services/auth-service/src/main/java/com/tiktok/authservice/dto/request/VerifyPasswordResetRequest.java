package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** First half of the two-step reset: the code from {@code forgot-password}, on its own. */
public record VerifyPasswordResetRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "\\d{6}") String otp
) {
}
