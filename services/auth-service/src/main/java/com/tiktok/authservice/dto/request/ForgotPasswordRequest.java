package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * @param resendTicket the ticket the previous reset request for this address answered with; it
 *                     stands in for {@code turnstileToken}. See {@code OtpSendGate}.
 */
public record ForgotPasswordRequest(
        @NotBlank @Email String email,
        String turnstileToken,
        String resendTicket
) {
}
