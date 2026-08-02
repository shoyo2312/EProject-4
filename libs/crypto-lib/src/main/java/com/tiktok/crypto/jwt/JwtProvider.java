package com.tiktok.crypto.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

/**
 * HMAC-signed JWT generation/validation. One instance per secret — services construct it
 * from their configured signing secret (e.g. via a @Configuration bean).
 */
public class JwtProvider {

    /** Claim naming the purpose a token was minted for. Written by auth-service on every token. */
    public static final String CLAIM_TOKEN_TYPE = "tokenType";
    public static final String TOKEN_TYPE_ACCESS = "access";
    public static final String TOKEN_TYPE_REFRESH = "refresh";

    private final SecretKey key;

    public JwtProvider(String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String generateToken(String subject, Map<String, Object> claims, long expiryMillis) {
        Date now = new Date();
        return Jwts.builder()
                .subject(subject)
                .claims(claims)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expiryMillis))
                .signWith(key)
                .compact();
    }

    public Claims extractClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractSubject(String token) {
        return extractClaims(token).getSubject();
    }

    public boolean isValid(String token) {
        try {
            extractClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Signature/expiry check PLUS an allow-list on the {@code tokenType} claim: only tokens
     * minted as access tokens pass. Every entry point that authenticates a caller from a bearer
     * token must use this rather than {@link #isValid(String)} — a refresh token is a validly
     * signed JWT too, so {@code isValid} alone lets a 7-day refresh token authenticate requests
     * and silently defeats the 15-minute access token TTL.
     *
     * <p>Allow-list rather than "reject tokenType == refresh": any token type added later is
     * rejected by default instead of silently becoming a bearer credential.
     */
    public boolean isValidAccessToken(String token) {
        try {
            return TOKEN_TYPE_ACCESS.equals(extractClaims(token).get(CLAIM_TOKEN_TYPE, String.class));
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }
}
