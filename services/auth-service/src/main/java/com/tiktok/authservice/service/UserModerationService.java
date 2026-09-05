package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    public void ban(Long userId, Long adminId) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            log.warn("Ban for unknown userId={} ignored", userId);
            return;
        }
        user.ban();
        userRepository.save(user);
        sessionRevoker.revokeAllSessions(userId);
        log.info("Banned userId={} by adminId={}", userId, adminId);
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
}
