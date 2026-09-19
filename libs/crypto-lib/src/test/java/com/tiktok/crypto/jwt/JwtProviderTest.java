package com.tiktok.crypto.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    private static final String SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    private final JwtProvider provider = new JwtProvider(SECRET);

    @Test
    void anAccessToken_isAcceptedAsOne() {
        String token = provider.generateToken("7", Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000);

        assertThat(provider.isValidAccessToken(token)).isTrue();
        assertThat(provider.extractSubject(token)).isEqualTo("7");
    }

    /** The whole reason isValidAccessToken exists: a 7-day refresh token must not authenticate. */
    @Test
    void aRefreshToken_isNotAnAccessToken() {
        String token = provider.generateToken("7", Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_REFRESH), 60_000);

        assertThat(provider.isValid(token)).isTrue();
        assertThat(provider.isValidAccessToken(token)).isFalse();
    }

    /** Allow-list, not deny-list: a token with no type, or a type added later, is refused. */
    @Test
    void aTokenWithoutAnAccessType_isRefused() {
        assertThat(provider.isValidAccessToken(provider.generateToken("7", Map.of(), 60_000))).isFalse();
        assertThat(provider.isValidAccessToken(provider.generateToken("7",
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, "mfa"), 60_000))).isFalse();
    }

    @Test
    void aTokenSignedWithAnotherSecret_isRefused() {
        String forged = new JwtProvider("another-secret-at-least-32-bytes-long-000000")
                .generateToken("7", Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000);

        assertThat(provider.isValidAccessToken(forged)).isFalse();
    }

    @Test
    void anExpiredToken_isRefused() {
        String expired = provider.generateToken("7", Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), -1_000);

        assertThat(provider.isValidAccessToken(expired)).isFalse();
    }

    @Test
    void garbage_isRefusedRatherThanThrown() {
        assertThat(provider.isValidAccessToken("not.a.jwt")).isFalse();
        assertThat(provider.isValidAccessToken("")).isFalse();
    }

    @Test
    void issuedAtMillis_isMillisecondPrecise() {
        long before = System.currentTimeMillis();
        String token = provider.generateToken("7", Map.of(), 60_000);

        assertThat(JwtProvider.issuedAtMillis(provider.extractClaims(token)))
                .isBetween(before, System.currentTimeMillis());
    }

    /** Tokens minted before iatMs existed fall back to the second-resolution iat. */
    @Test
    void issuedAtMillis_withoutTheMillisClaim_fallsBackToIat() {
        Date issuedAt = new Date(1_700_000_000_000L);
        String legacy = Jwts.builder().subject("7").issuedAt(issuedAt)
                .expiration(new Date(System.currentTimeMillis() + 60_000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        Claims claims = provider.extractClaims(legacy);
        assertThat(JwtProvider.issuedAtMillis(claims)).isEqualTo(1_700_000_000_000L);
    }
}
