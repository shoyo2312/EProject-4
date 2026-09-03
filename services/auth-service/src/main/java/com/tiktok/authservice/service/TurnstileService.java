package com.tiktok.authservice.service;

import com.tiktok.authservice.config.TurnstileProperties;
import com.tiktok.authservice.exception.TurnstileVerificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Gates every OTP-issuing call (register, addEmail, resendVerification, forgotPassword) behind
 * Cloudflare Turnstile — called first, before any other check or side effect. Fails closed: an
 * unreachable Cloudflare should deny the send, not wave it through.
 *
 * <p>The two timeouts below are why the client is built by hand instead of taken from
 * {@code RestClient.create()}, whose default factory has none at all. The call runs on the request
 * thread, so a Cloudflare edge that accepts the connection and then never answers parks that thread
 * for as long as the socket stays open. Four endpoints' worth of traffic against a silent
 * siteverify empties the servlet container's thread pool, and then the whole service stops
 * answering — login and refresh included, which never go anywhere near Turnstile. Bounding the wait
 * turns that into the {@link TurnstileVerificationException} this class already promises for an
 * unreachable Cloudflare: the OTP flows fail, and nothing else does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TurnstileService {

    private static final String VERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    private final TurnstileProperties turnstileProperties;
    private final RestClient restClient = RestClient.builder()
            .requestFactory(timeoutBoundRequestFactory())
            .build();

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

    /**
     * The JDK factory specifically, rather than whatever happens to be on the classpath: it is
     * always present, and both of its timeouts are settable here, so the bound holds no matter what
     * HTTP library another dependency drags in later.
     */
    private static ClientHttpRequestFactory timeoutBoundRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    private record SiteverifyResponse(boolean success) {
    }
}
