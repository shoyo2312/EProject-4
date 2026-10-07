package com.tiktok.crypto.jwt;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RevocationKeysTest {

    private final JwtProvider provider = new JwtProvider("test-secret-at-least-32-bytes-long-0123456789");

    /** Three read sides depend on these exact names; a change here must be deliberate. */
    @Test
    void keyNames_areTheContractTheReadSidesShare() {
        assertThat(RevocationKeys.forJti("abc")).isEqualTo("auth:blacklist:abc");
        assertThat(RevocationKeys.forUser(7L)).isEqualTo("auth:blacklist:user:7");
    }

    @Test
    void isIssuedBefore_comparesAgainstTheCutoff() {
        Claims claims = provider.extractClaims(provider.generateToken("7", Map.of(), 60_000));
        long issuedAt = JwtProvider.issuedAtMillis(claims);

        assertThat(RevocationKeys.isIssuedBefore(claims, String.valueOf(issuedAt + 1))).isTrue();
        assertThat(RevocationKeys.isIssuedBefore(claims, String.valueOf(issuedAt))).isFalse();
        assertThat(RevocationKeys.isIssuedBefore(claims, null)).isFalse();
    }

    @Test
    void keysOf_andIsRevoked_readOneMgetInOrder() {
        Claims withJti = provider.extractClaims(provider.generateToken("7", Map.of("jti", "abc"), 60_000));
        Claims withoutJti = provider.extractClaims(provider.generateToken("7", Map.of(), 60_000));
        String after = String.valueOf(JwtProvider.issuedAtMillis(withJti) + 1);

        assertThat(RevocationKeys.keysOf(withJti)).containsExactly("auth:blacklist:user:7", "auth:blacklist:abc");
        assertThat(RevocationKeys.keysOf(withoutJti)).containsExactly("auth:blacklist:user:7");

        assertThat(RevocationKeys.isRevoked(withJti, java.util.Arrays.asList(null, null))).isFalse();
        assertThat(RevocationKeys.isRevoked(withJti, java.util.Arrays.asList(null, "1"))).isTrue();
        assertThat(RevocationKeys.isRevoked(withJti, java.util.Arrays.asList(after, null))).isTrue();
        assertThat(RevocationKeys.isRevoked(withoutJti, java.util.Collections.singletonList(null))).isFalse();
    }

    @Test
    void jtiOf_readsTheJtiClaim() {
        Claims claims = provider.extractClaims(provider.generateToken("7", Map.of("jti", "abc"), 60_000));

        assertThat(RevocationKeys.jtiOf(claims)).isEqualTo("abc");
    }
}
