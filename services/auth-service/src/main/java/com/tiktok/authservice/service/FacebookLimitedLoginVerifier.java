package com.tiktok.authservice.service;

import com.tiktok.authservice.config.OAuthProperties;
import com.tiktok.authservice.entity.AuthProvider;
import com.tiktok.authservice.exception.InvalidSocialTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.security.Jwk;
import io.jsonwebtoken.security.JwkSet;
import io.jsonwebtoken.security.Jwks;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.security.Key;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies the OIDC token Facebook's <em>Limited Login</em> hands to the client instead of a Graph
 * access token.
 *
 * <p>iOS decides this, not us: when App Tracking Transparency has not been granted the Facebook SDK
 * downgrades the login and returns an ID token, so {@code flutter_facebook_auth} sends one even
 * though it asks for {@code LoginTracking.enabled}. Such a token is a JWT and carries no Graph
 * capability at all — {@code /debug_token} answers "Cannot parse access token" for it, which is why
 * a perfectly good login used to come back as "Social login token is invalid or expired".
 *
 * <p>Unlike an access token this one is self-describing and signed, so the check is local: the
 * signature against Facebook's published keys, {@code iss} against Facebook, and {@code aud}
 * against our own app id — the last is what stops a token minted for another app from logging its
 * bearer in as that user, the same job {@code app_id} does on the access-token path.
 */
@Component
class FacebookLimitedLoginVerifier {

    private static final String ISSUER = "https://www.facebook.com";
    private static final String JWKS_PATH = "/.well-known/oauth/openid/jwks/";

    private final RestClient facebookJwksRestClient;
    private final OAuthProperties properties;

    /**
     * Facebook rotates signing keys, and a JWKS fetch per login would put a second network round
     * trip inside every sign-in. Caching the whole set and refetching only when a token names a
     * {@code kid} we do not hold picks the rotation up on the first token signed with the new key.
     */
    private final AtomicReference<Map<String, PublicKey>> keys = new AtomicReference<>(Map.of());

    FacebookLimitedLoginVerifier(RestClient facebookJwksRestClient, OAuthProperties properties) {
        this.facebookJwksRestClient = facebookJwksRestClient;
        this.properties = properties;
    }

    SocialProfile verify(String idToken) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override
                        protected Key locate(JwsHeader header) {
                            return keyFor(header.getKeyId());
                        }
                    })
                    .requireIssuer(ISSUER)
                    .requireAudience(properties.facebook().appId())
                    .build()
                    .parseSignedClaims(idToken)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException | RestClientException e) {
            // Wrong signature, wrong app, expired, or Facebook not answering for its keys: none of
            // them is a token we may act on.
            throw new InvalidSocialTokenException();
        }

        String uid = claims.getSubject();
        if (uid == null || uid.isBlank()) {
            throw new InvalidSocialTokenException();
        }
        // `email` is present only when the permission was granted, exactly as on the Graph path.
        String email = claims.get("email", String.class);
        // Same on this path as on the Graph one: absent for an account with no picture of its own.
        String picture = claims.get("picture", String.class);
        return new SocialProfile(
                AuthProvider.FACEBOOK,
                uid,
                email == null || email.isBlank() ? null : email.toLowerCase(),
                // Facebook still asserts nothing about the address, so it may not auto-link.
                false,
                picture == null || picture.isBlank() ? null : picture);
    }

    private PublicKey keyFor(String kid) {
        if (kid == null) {
            throw new InvalidSocialTokenException();
        }
        PublicKey cached = keys.get().get(kid);
        if (cached != null) {
            return cached;
        }
        Map<String, PublicKey> fresh = fetchKeys();
        keys.set(fresh);
        PublicKey rotated = fresh.get(kid);
        if (rotated == null) {
            throw new InvalidSocialTokenException();
        }
        return rotated;
    }

    private Map<String, PublicKey> fetchKeys() {
        String jwks = facebookJwksRestClient.get().uri(JWKS_PATH).retrieve().body(String.class);
        if (jwks == null) {
            throw new InvalidSocialTokenException();
        }
        JwkSet set = Jwks.setParser().build().parse(jwks);
        Map<String, PublicKey> parsed = new HashMap<>();
        for (Jwk<?> jwk : set) {
            if (jwk.getId() != null && jwk.toKey() instanceof PublicKey publicKey) {
                parsed.put(jwk.getId(), publicKey);
            }
        }
        return Map.copyOf(parsed);
    }
}
