package com.tiktok.authservice.service;

import com.tiktok.authservice.config.MailProperties;
import com.tiktok.authservice.config.OtpProperties;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

class MailServiceImplTest {

    private static final OtpProperties OTP = new OtpProperties(900_000, 900_000, 300_000);

    /** Builds the verification mail and returns every text part concatenated, already decoded. */
    private String render(String logoUrl) throws Exception {
        return render(logoUrl, (mail) -> mail.sendVerificationOtp("user@example.com", "123456"));
    }

    private String render(String logoUrl, java.util.function.Consumer<MailServiceImpl> send) throws Exception {
        MimeMessage[] captured = new MimeMessage[1];
        JavaMailSenderImpl capturing = new JavaMailSenderImpl() {
            @Override
            protected void doSend(MimeMessage[] messages, Object[] originalMessages) {
                captured[0] = messages[0];
            }
        };
        MailProperties props =
                new MailProperties("no-reply@example.dev", "Nowaa", logoUrl, "https://example.dev");
        send.accept(new MailServiceImpl(capturing, props, OTP));
        return captured[0].getSubject() + "\n" + text(captured[0]);
    }

    private String text(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof MimeMultipart multipart) {
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                all.append(text(multipart.getBodyPart(i))).append('\n');
            }
            return all.toString();
        }
        return String.valueOf(content);
    }

    @Test
    void mailCarriesBrandRecipientAndCode() throws Exception {
        String mail = render("https://cdn.example.dev/logo.png");

        assertThat(mail).contains("Nowaa");
        assertThat(mail).contains("https://cdn.example.dev/logo.png");
        assertThat(mail).contains("https://example.dev");
        assertThat(mail).contains("user@example.com");
        assertThat(mail).contains("123456");
        assertThat(mail).contains("15 minutes");
    }

    @Test
    void blankLogoDropsTheImageTagInsteadOfShippingABrokenOne() throws Exception {
        String mail = render("");

        assertThat(mail).doesNotContain("<img");
        assertThat(mail).contains("Nowaa");
    }

    @Test
    void securityMailsStateWhenTheyWereRequestedAndOtpMailsDoNot() throws Exception {
        String admin = render("", mail -> mail.sendAdminLoginOtp("user@example.com", "123456"));
        String social = render("", mail -> mail.sendSocialLinkOtp("user@example.com", "123456", "Google"));
        String reset = render("", mail -> mail.sendPasswordResetOtp("user@example.com", "123456"));

        assertThat(admin).containsPattern("Requested at \\d{1,2} \\w{3} \\d{4}, \\d{2}:\\d{2} \\(GMT\\+7\\)");
        assertThat(social).contains("Requested at ");
        assertThat(reset).doesNotContain("Requested at");
    }
}
