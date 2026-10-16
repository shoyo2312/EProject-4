package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserRole;
import com.tiktok.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Applies an admin-service moderation decision to the account itself. Ban is what makes the
 * decision real: every sign-in path here already refuses anything but {@code ACTIVE}, so flipping
 * the status locks out future logins, and revoking the sessions cuts off the tokens already issued
 * (an access token is stateless and would otherwise stay valid for its full 15 minutes).
 *
 * <p>No inbox row: both operations are pure state assignments, so a redelivery lands on the same
 * result, and admin-service keys the topic by user id, so ban and unban for one account keep their
 * order on the partition.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserModerationService {

    private final UserRepository userRepository;
    private final SessionRevoker sessionRevoker;

    @Transactional
    public void ban(Long userId, Long adminId, String reason, Instant bannedUntil) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Ban for unknown userId={} ignored", userId);
            return;
        }
        // Refused here because this is the only service that knows the role. A ban revokes every
        // session, so one admin — or one stolen admin session — could lock every other admin out
        // of the console, and an admin who banned themselves would have nobody left to lift it.
        if (user.getRole() == UserRole.ADMIN) {
            log.warn("Ban of ADMIN userId={} by adminId={} refused: admin accounts are not banned",
                    userId, adminId);
            return;
        }
        user.ban(reason, bannedUntil);
        userRepository.save(user);
        sessionRevoker.revokeAllSessions(userId);
        log.info("Banned userId={} by adminId={} until={}", userId, adminId, bannedUntil);
    }

    @Transactional
    public void unban(Long userId, Long adminId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Unban for unknown userId={} ignored", userId);
            return;
        }
        user.unban();
        userRepository.save(user);
        log.info("Unbanned userId={} by adminId={}", userId, adminId);
    }

    /**
     * Ends every session and leaves the status alone — for an account someone else has signed in
     * to, where a ban would lock the owner out with them. Admin accounts included, unlike a ban:
     * an admin who is signed out signs back in, so this cannot lock the console.
     *
     * <p>ponytail: unlike ban and unban this is not a pure assignment — a redelivery signs the
     * account out a second time, so an owner who signed back in between the two deliveries has to
     * sign in once more. An inbox claim on the eventId would close that; this service has no
     * inbox table yet.
     */
    public void revokeSessions(Long userId, Long adminId) {
        if (userRepository.findById(userId).isEmpty()) {
            log.warn("Session revocation for unknown userId={} ignored", userId);
            return;
        }
        sessionRevoker.revokeAllSessions(userId);
        log.info("Revoked all sessions of userId={} by adminId={}", userId, adminId);
    }
}
