package com.tiktok.authservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "auth.jwt")
public record JwtProperties(
        String secret,
        long accessTokenExpiryMillis,
        long refreshTokenExpiryMillis,

        /**
         * How long after a refresh token is rotated its predecessor may come back without being
         * treated as a stolen chain. It covers one client retrying a refresh whose response it
         * never saw; anything later than this is a second holder, and ends every session for the
         * user. Raising it widens the window an attacker can replay in, so keep it at the scale
         * of a request timeout, not a session.
         */
        long rotationGraceMillis
) {
}
