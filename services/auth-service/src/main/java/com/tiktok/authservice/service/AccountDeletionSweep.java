package com.tiktok.authservice.service;

import com.tiktok.authservice.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Runs the deletions owners asked for once their grace period has passed, each in its own
 * transaction so one failure does not hold back the rest. Shaped like {@link ExpiredBanSweep}: an
 * account is deleted up to one interval after its date, which for a 30-day wait is nothing.
 *
 * <p>No leader election: two instances can list the same account, and {@link
 * AccountClosureService#eraseIfDue} re-reads the row, so the second finds it deleted and stops.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionSweep {

    private static final int BATCH = 100;

    private final UserRepository userRepository;
    private final AccountClosureService accountClosureService;

    @Scheduled(fixedDelayString = "${auth.account.deletion-sweep-interval-ms:3600000}")
    public void deleteDueAccounts() {
        List<Long> due = userRepository.findIdsDueForDeletion(Instant.now(), PageRequest.of(0, BATCH));
        for (Long userId : due) {
            try {
                accountClosureService.eraseIfDue(userId);
            } catch (RuntimeException e) {
                log.error("Could not delete user {}; the next sweep tries again", userId, e);
            }
        }
    }
}
