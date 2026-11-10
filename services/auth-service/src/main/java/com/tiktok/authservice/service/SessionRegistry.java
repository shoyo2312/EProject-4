package com.tiktok.authservice.service;

import com.tiktok.authservice.config.JwtProperties;
import com.tiktok.authservice.dto.response.SessionResponse;
import com.tiktok.authservice.entity.DeviceType;
import com.tiktok.authservice.entity.SecurityEventType;
import com.tiktok.authservice.entity.SessionEndReason;
import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.entity.UserSession;
import com.tiktok.authservice.exception.InvalidRefreshTokenException;
import com.tiktok.authservice.exception.SessionNotFoundException;
import com.tiktok.authservice.repository.RefreshTokenRepository;
import com.tiktok.authservice.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * The account's signed-in devices: opening one at sign-in, keeping it alive at refresh, listing
 * them, and ending them — from the device itself, from another device, or because the limit was
 * reached.
 *
 * <p><b>The limit</b> is one session per {@link DeviceType}: one laptop/PC and one phone/tablet.
 * A sign-in on a second device of the same type ends the first rather than being refused, so
 * losing a phone never locks its owner out. ADMIN accounts are exempt: the console is their only
 * client, and a second admin browser signing the first out mid-moderation helps nobody.
 *
 * <p><b>Ending a session</b> has three parts, and missing any one leaves the device signed in:
 * the session row, its refresh tokens (so it cannot renew), and a Redis marker on its id (so the
 * access token it already holds — valid for up to fifteen minutes — stops working now). The
 * marker is written after the transaction commits: written before, a rollback would leave a live
 * session whose every request fails.
 *
 * <p><b>Serialisation.</b> Two sign-ins of the same account cannot both miss each other, because
 * each first stamps {@code users.last_login_at} and holds that row lock until commit (see
 * {@link TokenIssuer}). A refresh and a sign-out of the same session both lock the session row
 * first ({@link UserSessionRepository#findByIdForUpdate}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionRegistry {

    static final String REMOTE_LOGOUT_DETAIL = "Signed out from another device";

    private final UserSessionRepository sessionRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final AccessTokenBlacklist accessTokenBlacklist;
    private final JwtProperties jwtProperties;
    private final SecurityEventRecorder securityEventRecorder;
    private final TransactionTemplate transactionTemplate;

    /**
     * A sign-in: ends the account's other session of the same type, then opens this one. Must run
     * inside the sign-in's transaction, after {@code last_login_at} is stamped.
     *
     * @param expiresAt when the session's first refresh token expires
     */
    public UserSession open(User user, RequestOrigin origin, Instant expiresAt) {
        Instant now = Instant.now();
        DeviceType type = DeviceType.fromUserAgent(origin.userAgent());

        if (user.getRole() != UserRole.ADMIN) {
            List<Long> replaced = sessionRepository.findActiveIds(user.getId(), type, now);
            if (!replaced.isEmpty()) {
                end(replaced, SessionEndReason.REPLACED, now);
                log.info("User {} signed in on another {} device; ended session(s) {}", user.getId(), type, replaced);
            }
        }

        return sessionRepository.save(UserSession.builder()
                .userId(user.getId())
                .deviceType(type)
                .deviceLabel(origin.deviceLabel())
                .deviceModel(truncate(origin.model(), 64))
                .ipAddress(truncate(origin.address(), 45))
                .location(truncate(origin.location(), 128))
                .createdAt(now)
                .lastActiveAt(now)
                .expiresAt(expiresAt)
                .build());
    }

    /**
     * A refresh: the session must still be live, and now lasts as long as the new token.
     * Throws {@link InvalidRefreshTokenException} when it was ended — the device was signed out
     * remotely or replaced, and the client should drop its tokens.
     */
    public void extend(Long sessionId, Long userId, Instant expiresAt) {
        Instant now = Instant.now();
        UserSession session = sessionRepository.findByIdForUpdate(sessionId)
                .filter(s -> s.getUserId().equals(userId) && s.isActive(now))
                .orElseThrow(InvalidRefreshTokenException::new);
        sessionRepository.touch(session.getId(), now, expiresAt);
    }

    @Transactional(readOnly = true)
    public List<SessionResponse> list(Long userId, Long currentSessionId) {
        return sessionRepository.findActiveByUserId(userId, Instant.now()).stream()
                .map(s -> new SessionResponse(s.getId(), s.getDeviceType(), s.getDeviceLabel(),
                        s.getDeviceModel(), s.getIpAddress(), s.getLocation(), s.getCreatedAt(),
                        s.getLastActiveAt(), s.getId().equals(currentSessionId)))
                .toList();
    }

    /** The owner signing one of their devices out — possibly the current one. */
    public void signOut(Long userId, Long sessionId) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            sessionRepository.findByIdForUpdate(sessionId)
                    .filter(s -> s.getUserId().equals(userId) && s.isActive(now))
                    .orElseThrow(SessionNotFoundException::new);
            end(List.of(sessionId), SessionEndReason.REMOTE_LOGOUT, now);
        });
        securityEventRecorder.record(userId, SecurityEventType.LOGOUT, REMOTE_LOGOUT_DETAIL);
    }

    /** "Sign out of all other devices". Returns how many were signed out. */
    public int signOutOthers(Long userId, Long currentSessionId) {
        Integer ended = transactionTemplate.execute(status -> {
            Instant now = Instant.now();
            List<Long> others = sessionRepository.findActiveIdsExcept(userId,
                    Objects.requireNonNullElse(currentSessionId, -1L), now);
            others.forEach(sessionRepository::findByIdForUpdate);
            end(others, SessionEndReason.REMOTE_LOGOUT, now);
            return others.size();
        });
        if (ended != null && ended > 0) {
            securityEventRecorder.record(userId, SecurityEventType.LOGOUT, REMOTE_LOGOUT_DETAIL);
        }
        return ended == null ? 0 : ended;
    }

    /**
     * The device signed itself out. Its refresh token is already revoked by the caller; this ends
     * the session row and cuts off any other access token it still holds.
     */
    public void endedByLogout(Long sessionId) {
        if (sessionId != null) {
            end(List.of(sessionId), SessionEndReason.LOGOUT, Instant.now());
        }
    }

    /**
     * Every session at once. Access tokens are already cut off by the user-wide cutoff that
     * {@link SessionRevoker} writes, so only the rows change here.
     */
    public void endAll(Long userId) {
        sessionRepository.revokeAllByUserId(userId, SessionEndReason.ALL, Instant.now());
    }

    /** Account erasure: the rows hold addresses and places, which go with the account. */
    public void forget(Long userId) {
        sessionRepository.deleteAllByUserId(userId);
    }

    private void end(Collection<Long> sessionIds, SessionEndReason reason, Instant now) {
        if (sessionIds.isEmpty()) {
            return;
        }
        sessionRepository.revoke(sessionIds, reason, now);
        refreshTokenRepository.revokeAllBySessionIds(sessionIds, now);
        afterCommit(() -> {
            Duration ttl = Duration.ofMillis(jwtProperties.accessTokenExpiryMillis());
            sessionIds.forEach(id -> accessTokenBlacklist.blacklistSession(id, ttl));
        });
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
