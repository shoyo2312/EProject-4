package com.tiktok.authservice.service;

public interface MailService {

    void sendVerificationOtp(String toEmail, String otp);

    void sendPasswordResetOtp(String toEmail, String otp);

    /**
     * @param provider named in the body on purpose: the recipient can only judge whether to ignore
     *                 this mail if they are told which account is asking to be attached.
     */
    void sendSocialLinkOtp(String toEmail, String otp, String provider);
}
