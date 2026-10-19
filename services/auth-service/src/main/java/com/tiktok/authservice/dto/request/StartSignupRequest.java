package com.tiktok.authservice.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * First step of the email-first signup: the address the code goes to. Calling it again resends.
 *
 * @param resendTicket the ticket the previous send to this address answered with; it stands in for
 *                     {@code turnstileToken}. See {@code OtpSendGate}.
 */
public record StartSignupRequest(
        @NotBlank @Email @Size(max = 255) String email,
        String turnstileToken,
        String resendTicket
) {
}
