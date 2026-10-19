package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * @param resendTicket the ticket the previous verification send to this address answered with,
 *                     from this endpoint or from {@code POST /email}; it stands in for
 *                     {@code turnstileToken}. See {@code OtpSendGate}.
 */
public record ResendVerificationRequest(
        @NotBlank @Email String email,
        String turnstileToken,
        String resendTicket
) {
}
