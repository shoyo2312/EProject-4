package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.entity.RefreshToken;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Mints the pair of tokens that ends every successful sign-in, and records the refresh token so it
 * can later be rotated and revoked.
 *
 * <p>Its own class because social login ends here too. Written twice, the two copies would drift —
 * and the half that matters is not the JWT but the {@code refresh_tokens} row: a session whose row
 * was never written cannot be rotated, revoked on logout, or killed by a password reset.
 */
@Component
@RequiredArgsConstructor
public class TokenIssuer {

    static final String CLAIM_ROLE = "role";
    static final String CLAIM_JTI = "jti";

    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenRepository refreshTokenRepository;

    public TokenResponse issue(User user) {
        String subject = String.valueOf(user.getId());

        String accessToken = jwtProvider.generateToken(
                subject,
                Map.of(CLAIM_ROLE, user.getRole().name(),
                        CLAIM_JTI, UUID.randomUUID().toString(),
                        JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS),
                jwtProperties.accessTokenExpiryMillis());

        String refreshToken = jwtProvider.generateToken(
                subject,
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_REFRESH,
                        CLAIM_JTI, UUID.randomUUID().toString()),
                jwtProperties.refreshTokenExpiryMillis());

        RefreshToken tokenRecord = RefreshToken.builder()
                .userId(user.getId())
                .tokenHash(HashUtils.sha256(refreshToken))
                .expiresAt(Instant.now().plusMillis(jwtProperties.refreshTokenExpiryMillis()))
                .build();
        refreshTokenRepository.save(tokenRecord);

        return new TokenResponse(accessToken, refreshToken, jwtProperties.accessTokenExpiryMillis());
    }
}
