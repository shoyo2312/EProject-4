package com.tiktok.authservice.event.local;

/**
 * The signed-in owner of an account with no password asked for a code to close it. In-JVM Spring
 * event like its siblings, mailed by the AFTER_COMMIT listener.
 */
public record AccountClosureCodeRequestedEvent(String email, String otp) {
}
