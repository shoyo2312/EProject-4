package com.tiktok.authservice.service;

import com.tiktok.authservice.config.TurnstileProperties;
import com.tiktok.authservice.exception.TurnstileVerificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Gates every OTP-issuing call (register, addEmail, resendVerification, forgotPassword) behind
 * Cloudflare Turnstile — called first, before any other check or side effect. Fails closed: an
 * unreachable Cloudflare should deny the send, not wave it through.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnstileService {

    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final TurnstileProperties turnstileProperties;
    private final RestClient restClient = RestClient.create();

    public void verify(String token) {
        if (token == null || token.isBlank()) {
            throw new TurnstileVerificationException();
        }

        String body = "secret=" + URLEncoder.encode(turnstileProperties.secretKey(), StandardCharsets.UTF_8)
                + "&response=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

        SiteverifyResponse response;
        try {
            response = restClient.post()
                    .uri(VERIFY_URL)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body)
                    .retrieve()
                    .body(SiteverifyResponse.class);
        } catch (RestClientException e) {
            log.warn("Turnstile siteverify call failed, failing closed", e);
            throw new TurnstileVerificationException();
        }

        if (response == null || !response.success()) {
            throw new TurnstileVerificationException();
        }
    }

    private record SiteverifyResponse(boolean success) {
    }
}
