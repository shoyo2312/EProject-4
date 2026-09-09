package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Second step of admin-console login: the identifier from step one, the mailed code, a fresh
 * Turnstile token (the one from step one is spent), and whether to trust this browser for 30 days.
 */
public record AdminLoginOtpRequest(
        @NotBlank String usernameOrEmail,
        @NotBlank String otp,
        @NotBlank String turnstileToken,
        boolean rememberDevice
) {
}
