package com.tiktok.authservice.event.local;

import com.tiktok.authservice.service.MailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailNotificationListener {

    private final MailService mailService;

    /**
     * Prints the OTP to the log so manual testing works without reading a real mailbox. Off unless
     * {@code OTP_LOG_TO_CONSOLE} is set, because an OTP in a log is an account takeover for anyone
     * who can read logs — worst of all the social-link code, which grafts a provider account onto
     * an existing one, and the admin-login code, which is the console's second factor.
     */
    @Value("${auth.otp.log-to-console}")
    private final boolean logOtpToConsole;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmailVerificationRequested(EmailVerificationRequestedEvent event) {
        try {
            logOtp("verification OTP", event.email(), event.otp());
            mailService.sendVerificationOtp(event.email(), event.otp());
        } catch (Exception e) {
            log.error("Failed to send verification email to {}", event.email(), e);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSocialLinkRequested(SocialLinkRequestedEvent event) {
        try {
            logOtp("social OTP", event.email(), event.otp());
            mailService.sendSocialLinkOtp(event.email(), event.otp(), providerName(event));
        } catch (Exception e) {
            log.error("Failed to send social link email to {}", event.email(), e);
        }
    }

    private void logOtp(String purpose, String email, String otp) {
        if (logOtpToConsole) {
            log.warn("[otp-log enabled] {} for {} = {}", purpose, email, otp);
        }
    }

    /**
     * "FACEBOOK" is shouting at the reader; the mail says Facebook.
     */
    private String providerName(SocialLinkRequestedEvent event) {
        String name = event.provider().name().toLowerCase();
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAdminLoginOtpRequested(AdminLoginOtpRequestedEvent event) {
        try {
            logOtp("admin login OTP", event.email(), event.otp());
            mailService.sendAdminLoginOtp(event.email(), event.otp());
        } catch (Exception e) {
            log.error("Failed to send admin login email to {}", event.email(), e);
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        try {
            logOtp("password reset OTP", event.email(), event.otp());
            mailService.sendPasswordResetOtp(event.email(), event.otp());
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}", event.email(), e);
        }
    }
}
