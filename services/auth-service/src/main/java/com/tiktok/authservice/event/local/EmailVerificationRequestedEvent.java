package com.tiktok.authservice.event.local;

/**
 * In-JVM Spring event (not a Kafka event — no other service needs this). Published inside the
 * same transaction as OTP creation and only actually sent by the AFTER_COMMIT listener, so a
 * rolled-back transaction never triggers an email.
 */
public record EmailVerificationRequestedEvent(String email, String otp) {
}
