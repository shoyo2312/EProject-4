package com.tiktok.authservice.event.local;

import com.tiktok.authservice.entity.AuthProvider;

/**
 * Someone signed in with a {@code provider} account carrying this address, and that address
 * already belongs to an account here. The mail is both the code and the warning: if the recipient
 * did not just do this, somebody else is trying to attach their provider account to it, and the
 * only correct response is to ignore the mail.
 *
 * <p>In-JVM Spring event like its siblings, published inside the OTP's transaction and sent only
 * by the AFTER_COMMIT listener, so a rolled-back challenge never mails anything.
 */
public record SocialLinkRequestedEvent(String email, String otp, AuthProvider provider) {
}
