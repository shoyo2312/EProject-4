package com.tiktok.authservice.service;

import com.tiktok.authservice.config.MailProperties;
import com.tiktok.authservice.config.OtpProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;
    private final OtpProperties otpProperties;

    @Override
    public void sendVerificationOtp(String toEmail, String otp) {
        send(toEmail, "Verify your email", "Verify your email",
                "Enter this code to finish setting up your account.",
                otp, otpProperties.emailVerificationExpiryMillis(),
                "If you didn't create an account, you can ignore this email.");
    }

    @Override
    public void sendPasswordResetOtp(String toEmail, String otp) {
        send(toEmail, "Reset your password", "Reset your password",
                "Enter this code to choose a new password.",
                otp, otpProperties.passwordResetExpiryMillis(),
                "If you didn't ask for a password reset, ignore this email — your password stays as it is.");
    }

    @Override
    public void sendSocialLinkOtp(String toEmail, String otp, String provider) {
        send(toEmail, "Confirm your " + provider + " sign-in", "Confirm your " + provider + " sign-in",
                "Someone signed in with a " + provider + " account using this email address and wants "
                        + "to attach it to your account.",
                otp, otpProperties.emailVerificationExpiryMillis(),
                "If this wasn't you, ignore this email — nothing changes until the code is entered.");
    }

    /**
     * Taken from the property the OTP's own expiry is computed from, rather than written into the
     * body as "15 minutes". The two were free to drift, and a mail that states a lifetime the code
     * does not have sends the user looking for a code that has already died.
     */
    private String expiry(long millis) {
        long minutes = Duration.ofMillis(millis).toMinutes();
        return minutes == 1 ? "1 minute" : minutes + " minutes";
    }

    private void send(String toEmail, String subject, String heading, String intro,
                      String otp, long expiryMillis, String footnote) {
        String expiry = expiry(expiryMillis);
        String text = intro + "\n\nYour code is " + otp + ". It expires in " + expiry + ".\n\n" + footnote;
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true so the plain-text part survives for clients that refuse HTML.
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailProperties.from());
            helper.setTo(toEmail);
            helper.setSubject(subject);
            helper.setText(text, html(heading, intro, otp, expiry, footnote));
            mailSender.send(message);
        } catch (MessagingException e) {
            throw new MailPreparationException(e);
        }
    }

    /**
     * Table layout + inline styles on purpose: Gmail strips &lt;style&gt; blocks and most clients
     * ignore flex/grid. Escaped because the provider name reaches the heading from the OAuth response.
     */
    private String html(String heading, String intro, String otp, String expiry, String footnote) {
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" \
                style="background:#f4f4f5;padding:32px 12px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif">
                  <tr><td align="center">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" \
                style="max-width:440px;background:#ffffff;border-radius:12px;padding:36px 32px">
                      <tr><td style="font-size:20px;font-weight:600;color:#18181b;padding-bottom:12px">%s</td></tr>
                      <tr><td style="font-size:15px;line-height:22px;color:#52525b;padding-bottom:24px">%s</td></tr>
                      <tr><td align="center" style="padding:18px 0;background:#fafafa;border-radius:10px;\
                font-size:32px;font-weight:700;letter-spacing:8px;color:#18181b;font-family:monospace">%s</td></tr>
                      <tr><td style="font-size:14px;color:#71717a;padding-top:16px">This code expires in %s.</td></tr>
                      <tr><td style="font-size:13px;line-height:20px;color:#a1a1aa;padding-top:24px;\
                border-top:1px solid #e4e4e7">%s</td></tr>
                    </table>
                  </td></tr>
                </table>
                """.formatted(
                HtmlUtils.htmlEscape(heading),
                HtmlUtils.htmlEscape(intro),
                HtmlUtils.htmlEscape(otp),
                expiry,
                HtmlUtils.htmlEscape(footnote));
    }
}
