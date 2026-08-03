package com.tiktok.authservice.event.local;

import com.tiktok.authservice.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationListener {

    private final MailService mailService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailVerificationRequested(EmailVerificationRequestedEvent event) {
        try {
            mailService.sendVerificationOtp(event.email(), event.otp());
        } catch (Exception e) {
            log.error("Failed to send verification email to {}", event.email(), e);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        try {
            mailService.sendPasswordResetOtp(event.email(), event.otp());
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}", event.email(), e);
        }
    }
}
