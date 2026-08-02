package com.tiktok.authservice.service;

public interface MailService {

    void sendVerificationOtp(String toEmail, String otp);

    void sendPasswordResetOtp(String toEmail, String otp);
}
