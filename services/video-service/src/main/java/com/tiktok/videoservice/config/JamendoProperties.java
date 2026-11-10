package com.tiktok.videoservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The music catalogue (Jamendo, api.jamendo.com/v3.0). The client id is the one from the
 * developer dashboard's application page — only the id: reading the catalogue needs no secret,
 * and the secret is for OAuth, which nothing here does. It stays on the server so no app build
 * carries it, and the quota it is held to is spent only by signed-in users.
 *
 * @param clientId blank switches music off: the catalogue answers 503 MUSIC_UNAVAILABLE
 */
@ConfigurationProperties(prefix = "app.music.jamendo")
public record JamendoProperties(String clientId, String baseUrl, Duration timeout) {

    public JamendoProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.jamendo.com/v3.0" : baseUrl;
        timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }

    public boolean configured() {
        return clientId != null && !clientId.isBlank();
    }
}
