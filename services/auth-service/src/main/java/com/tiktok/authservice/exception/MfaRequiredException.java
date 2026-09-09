package com.tiktok.authservice.exception;

import com.tiktok.common.exception.DomainException;
import org.springframework.http.HttpStatus;

/**
 * Password and Turnstile were accepted and the account is an ADMIN, so a second factor is owed
 * before any session is issued. A code has already been mailed; the client must collect it and
 * call {@code POST /api/v1/auth/login/otp}.
 *
 * <p>Its own code, distinct from {@link InvalidCredentialsException} and
 * {@link EmailNotVerifiedException}, because the console has to tell these apart to switch the
 * form to its OTP step. Carries nothing about the account — the client already holds the
 * identifier it typed.
 */
public class MfaRequiredException extends DomainException {

    public MfaRequiredException() {
        super("MFA_REQUIRED", "A verification code has been sent to your email", HttpStatus.UNAUTHORIZED);
    }
}
