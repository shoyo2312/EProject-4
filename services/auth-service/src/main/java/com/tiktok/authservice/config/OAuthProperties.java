package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Credentials identifying <em>us</em> to Google and Facebook. They are what makes a provider token
 * checkable: without them any token from any app would pass. Values come from env vars only —
 * {@code app-secret} in particular is a password for our Facebook app.
 */
@ConfigurationProperties(prefix = "auth.oauth")
public record OAuthProperties(
        Google google,
        Facebook facebook
) {

    /**
     * @param clientIds every OAuth client id we ship, one per platform: the Next.js web client, the
     *                  iOS app and the Android app each get their own from Google Cloud Console,
     *                  and the {@code aud} of an ID token is whichever one signed the user in. A
     *                  missing entry here means that platform's users cannot log in at all, so all
     *                  of them are listed rather than only the web one.
     */
    public record Google(List<String> clientIds) {
    }

    public record Facebook(String appId, String appSecret) {
    }
}
