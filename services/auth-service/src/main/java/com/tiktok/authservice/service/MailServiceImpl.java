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

import java.io.UnsupportedEncodingException;
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
    public void sendAdminLoginOtp(String toEmail, String otp) {
        send(toEmail, "Your admin sign-in code", "Admin console sign-in",
                "Enter this code to finish signing in to the admin console.",
                otp, otpProperties.adminLoginExpiryMillis(),
                "If this wasn't you, someone has your password — change it now.");
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
        String brand = mailProperties.brandName();
        String text = brand + "\n\n" + intro + "\n\nYour code is " + otp + ". It expires in " + expiry
                + ".\n\n" + footnote + "\n\nThis email was sent to " + toEmail + " by " + brand
                + " (" + mailProperties.siteUrl() + ").";
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true so the plain-text part survives for clients that refuse HTML.
            MimeMessageHelper helper =
                    new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(mailProperties.from(), brand);
            helper.setTo(toEmail);
            helper.setSubject(brand + " · " + subject);
            helper.setText(text, html(heading, intro, otp, expiry, footnote, toEmail));
            mailSender.send(message);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException(e);
        }
    }

    /**
     * Logo as an &lt;img&gt; with the brand name as alt text, not as the only header content:
     * most clients block remote images until the reader asks for them, so the name has to survive
     * on its own. Blank {@code logo-url} drops the tag entirely rather than shipping a broken one.
     */
    private String header() {
        String brand = HtmlUtils.htmlEscape(mailProperties.brandName());
        String logoUrl = mailProperties.logoUrl();
        String logo = (logoUrl == null || logoUrl.isBlank()) ? "" :
                "<img src=\"" + HtmlUtils.htmlEscape(logoUrl) + "\" alt=\"" + brand + "\" width=\"40\" "
                        + "height=\"40\" style=\"display:block;margin:0 auto 10px;border:0\">";
        return logo + "<div style=\"font-size:16px;font-weight:700;color:#18181b\">" + brand + "</div>";
    }

    /**
     * Table layout + inline styles on purpose: Gmail strips &lt;style&gt; blocks and most clients
     * ignore flex/grid. Escaped because the provider name reaches the heading from the OAuth response.
     */
    private String html(String heading, String intro, String otp, String expiry, String footnote,
                        String toEmail) {
        String siteUrl = HtmlUtils.htmlEscape(mailProperties.siteUrl());
        return """
                <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" \
                style="background:#f4f4f5;padding:32px 12px;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,Helvetica,Arial,sans-serif">
                  <tr><td align="center">
                    <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" \
                style="max-width:440px;background:#ffffff;border-radius:12px;padding:36px 32px">
                      <tr><td align="center" style="padding-bottom:24px">%s</td></tr>
                      <tr><td style="font-size:20px;font-weight:600;color:#18181b;padding-bottom:12px">%s</td></tr>
                      <tr><td style="font-size:15px;line-height:22px;color:#52525b;padding-bottom:8px">Hi %s,</td></tr>
                      <tr><td style="font-size:15px;line-height:22px;color:#52525b;padding-bottom:24px">%s</td></tr>
                      <tr><td align="center" style="padding:18px 0;background:#fafafa;border-radius:10px;\
                font-size:32px;font-weight:700;letter-spacing:8px;color:#18181b;font-family:monospace">%s</td></tr>
                      <tr><td style="font-size:14px;color:#71717a;padding-top:16px">This code expires in %s.</td></tr>
                      <tr><td style="font-size:13px;line-height:20px;color:#a1a1aa;padding-top:24px;\
                border-top:1px solid #e4e4e7">%s</td></tr>
                      <tr><td style="font-size:12px;line-height:18px;color:#a1a1aa;padding-top:16px">\
                This email was sent to %s by %s — <a href="%s" style="color:#71717a">%s</a></td></tr>
                    </table>
                  </td></tr>
                </table>
                """.formatted(
                header(),
                HtmlUtils.htmlEscape(heading),
                HtmlUtils.htmlEscape(toEmail),
                HtmlUtils.htmlEscape(intro),
                HtmlUtils.htmlEscape(otp),
                expiry,
                HtmlUtils.htmlEscape(footnote),
                HtmlUtils.htmlEscape(toEmail),
                HtmlUtils.htmlEscape(mailProperties.brandName()),
                siteUrl,
                siteUrl);
    }
}
