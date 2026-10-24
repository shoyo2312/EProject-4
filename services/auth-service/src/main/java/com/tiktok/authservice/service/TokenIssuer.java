package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.dto.response.TokenResponse;
import com.tiktok.authservice.entity.RefreshToken;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.event.producer.UserEventProducer;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserRepository;
import com.tiktok.crypto.hash.HashUtils;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.event.user.NewDeviceSignInEvent;
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

    /** The security-log detail of a sign-in that raised a new-device alert. */
    static final String NEW_DEVICE_DETAIL = "New device";

    private final JwtProvider jwtProvider;
    private final JwtProperties jwtProperties;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;
    private final SecurityEventRecorder securityEventRecorder;
    private final TransactionTemplate transactionTemplate;
    private final RequestOrigins requestOrigins;
    private final KnownDevices knownDevices;
    private final UserEventProducer userEventProducer;

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
     * <p>It is also where a sign-in from a new device is noticed (spec §6). The device is
     * remembered and the alert's outbox row written inside the same transaction as the refresh
     * token, so a sign-in that rolls back leaves neither, and an alert cannot be lost after its
     * device was recorded as known. The request is read once, before, so the log entry and the
     * alert name the same device and place.
     *
     * @param signIn how the account was proven, for the log
     */
    public TokenResponse issue(User user, SecurityEventType signIn) {
        RequestOrigin origin = requestOrigins.current();
        Minted minted = transactionTemplate.execute(status -> {
            TokenResponse tokens = mint(user);
            return new Minted(tokens, alertIfNewDevice(user, origin));
        });
        securityEventRecorder.record(user.getId(), signIn, minted.newDevice() ? NEW_DEVICE_DETAIL : null, origin);
        return minted.tokens();
    }

    /**
     * ADMIN accounts are left out: they sign in only in the console, which shows no inbox from
     * notification-service, so the alert would land where nobody can read it.
     */
    private boolean alertIfNewDevice(User user, RequestOrigin origin) {
        if (user.getRole() == UserRole.ADMIN || !knownDevices.signedIn(user.getId(), origin.deviceId())) {
            return false;
        }
        userEventProducer.publishNewDeviceSignIn(
                NewDeviceSignInEvent.of(user.getId(), origin.alertLabel(), origin.location()));
        return true;
    }

    private record Minted(TokenResponse tokens, boolean newDevice) {
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
     *
     * <p>It does remember the device, and never alerts. A signed-in browser refreshes every
     * fifteen minutes and may never sign in again, so this is how the browsers people already use
     * become known — without it, the first sign-in after the deploy, an attacker's included, would
     * be the account's "first device" and pass in silence.
     */
    @Transactional
    public TokenResponse reissue(User user) {
        TokenResponse tokens = mint(user);
        if (user.getRole() != UserRole.ADMIN) {
            knownDevices.refreshed(user.getId(), requestOrigins.current().deviceId());
        }
        return tokens;
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
