package com.tiktok.authservice.service;

import com.tiktok.authservice.entity.User;
import com.tiktok.authservice.entity.UserStatus;
import com.tiktok.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Lifts bans that have run out.
 *
 * <p>A temporary ban needs something to end it, and nothing else would: admin-service publishes
 * the decision once and then has no further say, and the account's standing is read from this
 * row by the directory, the profile and every sign-in path alike. Lifting it lazily — on
 * whichever of those happened to read the row first — would have the console still showing
 * BANNED while the person was logging in perfectly well.
 *
 * <p>The window is the cost: an account stays locked out for up to one interval past its
 * deadline. For a ban measured in days that is not worth a scheduler with second resolution.
 *
 * <p>No leader election: two instances running this at once both write ACTIVE over a row that
 * was BANNED, which is the same result. It is the one shape of concurrent write that needs no
 * coordinating.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExpiredBanSweep {

    private final UserRepository userRepository;

    @Scheduled(fixedDelayString = "${auth.bans.sweep-interval-ms:300000}")
    @Transactional
    public void liftExpiredBans() {
        List<User> lapsed = userRepository.findByStatusAndBannedUntilBefore(UserStatus.BANNED, Instant.now());
        if (lapsed.isEmpty()) {
            return;
        }

        // unban() clears bannedUntil as well, so a row cannot come back on the next sweep.
        lapsed.forEach(User::unban);
        userRepository.saveAll(lapsed);
        log.info("Lifted {} expired ban(s)", lapsed.size());
    }
}
