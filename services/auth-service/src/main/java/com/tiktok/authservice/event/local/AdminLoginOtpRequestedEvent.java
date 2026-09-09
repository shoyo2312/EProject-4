package com.tiktok.authservice.event.local;

/**
 * An ADMIN passed the password step of console login and needs the second factor mailed.
 * In-JVM Spring event like its siblings, published inside the OTP's transaction and sent only by
 * the AFTER_COMMIT listener, so a rolled-back challenge mails nothing.
 */
public record AdminLoginOtpRequestedEvent(String email, String otp) {
}
