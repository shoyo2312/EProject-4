package com.tiktok.authservice.event.local;

import com.tiktok.authservice.service.MailService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class EmailNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationListener.class);

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
