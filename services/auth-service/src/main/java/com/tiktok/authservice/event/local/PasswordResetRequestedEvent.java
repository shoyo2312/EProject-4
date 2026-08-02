package com.tiktok.authservice.event.local;

public record PasswordResetRequestedEvent(String email, String otp) {
}
