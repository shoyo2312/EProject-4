package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Ends every live session for one user: refresh tokens are revoked in the database, and access
 * tokens — stateless, so otherwise valid until they expire — are cut off by a user-wide Redis
 * marker that all three read sides check (this service's JwtAuthenticationFilter, security-lib's
 * filter for downstream services, api-gateway at the edge).
 *
 * <p>Its own bean, and its own transaction, on purpose: the refresh-token replay path revokes and
 * then throws to reject the caller. Run inline, the throw would roll the revocation back and the
 * attacker would keep the chain — the detection would log a warning and change nothing.
 */
@Component
@RequiredArgsConstructor
public class SessionRevoker {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final JwtProperties jwtProperties;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllSessions(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, Instant.now());
        accessTokenBlacklist.blacklistAllForUser(userId,
                Duration.ofMillis(jwtProperties.accessTokenExpiryMillis()));
    }
}
