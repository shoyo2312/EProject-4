package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

/**
 * Wiring for social login. Mirrors {@link JwtConfig}: the credentials are validated at startup so a
 * deployment missing them fails on boot rather than on the first user who taps "Sign in with
 * Google" — a misconfiguration that would otherwise surface as a 500 in production only.
 */
@Configuration
@EnableConfigurationProperties(OAuthProperties.class)
public class OAuthConfig {

    private static final String GOOGLE_BASE_URL = "https://oauth2.googleapis.com";
    private static final String FACEBOOK_BASE_URL = "https://graph.facebook.com/v21.0";

    /**
     * Both calls sit inside a user's login request, so they get explicit timeouts: the default is
     * infinite, and a provider that stops answering would otherwise hold a request thread until the
     * client gives up.
     */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient googleRestClient(OAuthProperties properties, Environment environment) {
        if (!environment.matchesProfiles("local", "test") && isEmpty(properties.google().clientIds())) {
            throw new IllegalStateException(
                    "GOOGLE_CLIENT_IDS is not set. Without it every Google ID token would be accepted, "
                            + "including tokens issued to other applications.");
        }
        return build(GOOGLE_BASE_URL);
    }

    @Bean
    public RestClient facebookRestClient(OAuthProperties properties, Environment environment) {
        if (!environment.matchesProfiles("local", "test")
                && (isBlank(properties.facebook().appId()) || isBlank(properties.facebook().appSecret()))) {
            throw new IllegalStateException(
                    "FB_APP_ID and FB_APP_SECRET are not set. Facebook tokens cannot be checked against "
                            + "our own app without them.");
        }
        return build(FACEBOOK_BASE_URL);
    }

    private RestClient build(String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    private static boolean isEmpty(List<String> values) {
        return values == null || values.isEmpty();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
