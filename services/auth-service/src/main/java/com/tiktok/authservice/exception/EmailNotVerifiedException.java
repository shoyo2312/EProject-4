package com.tiktok.authservice.exception;

import com.tiktok.common.exception.ForbiddenException;

/**
 * Credentials were correct but the account has not confirmed its email address yet.
 *
 * <p>Deliberately distinct from {@link InvalidCredentialsException}: the caller has already
 * proven it knows the password, so there is no account existence left to hide, and the client
 * needs to tell the two apart to route the user to the "resend verification code" screen.
 */
public class EmailNotVerifiedException extends ForbiddenException {

    public EmailNotVerifiedException() {
        super("EMAIL_NOT_VERIFIED", "Email address must be verified before signing in");
    }
}
