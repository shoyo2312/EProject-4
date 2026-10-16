package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Second half of the two-step reset. No address: the account is whichever one the ticket was
 * issued for, so it cannot be swapped for another.
 */
public record CompletePasswordResetRequest(
        @NotBlank String resetToken,
        @NotBlank @Size(min = 8, max = 100) @FitsBcrypt String newPassword
) {
}
