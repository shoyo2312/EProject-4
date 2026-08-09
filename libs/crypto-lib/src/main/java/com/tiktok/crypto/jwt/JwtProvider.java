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

    /**
     * Issue time in epoch millis. The standard {@code iat} claim only has second resolution,
     * which is too coarse for "revoke every token issued before now": a token minted in the same
     * second as the cutoff cannot be told apart from one minted just after it, so either a stolen
     * token survives or a fresh login is rejected.
     */
    public static final String CLAIM_ISSUED_AT_MILLIS = "iatMs";

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
                .claim(CLAIM_ISSUED_AT_MILLIS, now.getTime())
                .expiration(new Date(now.getTime() + expiryMillis))
                .signWith(key)
                .compact();
    }

    /**
     * When this token was issued, in epoch millis. Falls back to the second-resolution {@code iat}
     * for tokens minted before {@link #CLAIM_ISSUED_AT_MILLIS} existed — truncating to the start
     * of the second, so a revocation cutoff errs towards rejecting such a token rather than
     * letting it through.
     */
    public static long issuedAtMillis(Claims claims) {
        Long issuedAtMillis = claims.get(CLAIM_ISSUED_AT_MILLIS, Long.class);
        if (issuedAtMillis != null) {
            return issuedAtMillis;
        }
        return claims.getIssuedAt() == null ? 0L : claims.getIssuedAt().getTime();
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
