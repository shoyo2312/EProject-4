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
        MimeMessage[] captured = new MimeMessage[1];
        JavaMailSenderImpl capturing = new JavaMailSenderImpl() {
            @Override
            protected void doSend(MimeMessage[] messages, Object[] originalMessages) {
                captured[0] = messages[0];
            }
        };
        MailProperties props =
                new MailProperties("no-reply@example.dev", "Nowaa", logoUrl, "https://example.dev");
        new MailServiceImpl(capturing, props, OTP).sendVerificationOtp("user@example.com", "123456");
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
}
