package com.tiktok.authservice.service;

import com.tiktok.authservice.config.MailProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    @Override
    public void sendVerificationOtp(String toEmail, String otp) {
        send(toEmail, "Verify your email",
                "Your verification code is " + otp + ". It expires in 15 minutes.");
    }

    @Override
    public void sendPasswordResetOtp(String toEmail, String otp) {
        send(toEmail, "Reset your password",
                "Your password reset code is " + otp + ". It expires in 15 minutes.");
    }

    private void send(String toEmail, String subject, String body) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailProperties.from());
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
    }
}
