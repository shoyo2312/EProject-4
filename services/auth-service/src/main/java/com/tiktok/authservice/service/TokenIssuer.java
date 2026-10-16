package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.entity.RefreshToken;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p>Transactional here rather than only at the callers, because this is the one write on the login
 * path and {@code login} deliberately no longer opens a transaction of its own — see
 * {@link AuthServiceImpl#login}. Callers that already run in one, {@code refresh} and the social
 * flows, simply join it: the row still commits with whatever else they did, exactly as before.
 */
@Component
@RequiredArgsConstructor
public class TokenIssuer {

    static final String CLAIM_ROLE = "role";
    static final String CLAIM_JTI = "jti";

    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final SecurityEventRecorder securityEventRecorder;
    private final TransactionTemplate transactionTemplate;

    /**
     * A sign-in. Mints the pair and appends {@code signIn} to the account's security log, so every
     * way of proving an account — password, admin OTP, social — is recorded in one place rather
     * than at each of the four call sites that would otherwise have to remember to.
     *
     * <p>The entry is written after the tokens commit, not inside their transaction. The recorder
     * opens a transaction of its own ({@code REQUIRES_NEW}, see {@link SecurityEventRecorder}), so
     * inside this one every sign-in held two pooled connections at once, and a login burst could
     * fill the pool with first connections all waiting for a second. A caller that is already in a
     * transaction still joins it here, as before.
     *
     * @param signIn how the account was proven, for the log
     */
    public TokenResponse issue(User user, SecurityEventType signIn) {
        TokenResponse tokens = transactionTemplate.execute(status -> mint(user));
        securityEventRecorder.record(user.getId(), signIn);
        return tokens;
    }

    /**
     * A refresh. The same pair is minted and {@code last_login_at} still advances, but nothing is
     * appended to the security log.
     *
     * <p>ponytail: a signed-in client refreshes every fifteen minutes, so recording rotations
     * would outnumber every other security event by two orders of magnitude — to say that a
     * session the log already holds is still alive. The one rotation worth an entry is the one
     * from an address the account has not used before; add it here, behind that comparison, if
     * spotting a stolen refresh token ever matters more than the rows it costs.
     */
    @Transactional
    public TokenResponse reissue(User user) {
        return mint(user);
    }

    private TokenResponse mint(User user) {
        String subject = String.valueOf(user.getId());

        // The one place every sign-in path ends, so the one place last_login_at is stamped. A bulk
        // update rather than a change to the entity: saving it bumped the row's version, and two
        // devices refreshing at once then failed one of them with 409 (see stampLastLogin).
        userRepository.stampLastLogin(user.getId(), Instant.now());

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
