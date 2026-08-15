package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The address a social account is claiming, still to be proven by the OTP that follows. */
public record AddEmailRequest(
        @NotBlank @Email @Size(max = 255) String email
) {
}
