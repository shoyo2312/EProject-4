package com.tiktok.userservice.service;

import com.tiktok.userservice.event.producer.UserEventPublisher;
import com.tiktok.userservice.repository.FollowRequestRepository;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserMuteRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import com.tiktok.userservice.repository.UserSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountStateServiceImpl implements AccountStateService {

    private final UserProfileRepository userProfileRepository;
    private final UserFollowRepository userFollowRepository;
    private final FollowRequestRepository followRequestRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserMuteRepository userMuteRepository;
    private final UserSettingsRepository userSettingsRepository;
    private final UserEventPublisher userEventPublisher;

    @Override
    @Transactional
    public void applyDeactivated(Long userId, boolean deactivated, Instant changedAt) {
        int applied = userProfileRepository.applyAccountState(userId, deactivated ? changedAt : null, changedAt);
        if (applied == 0) {
            log.info("Account state for user {} at {} not applied: no profile, or a newer change is recorded",
                    userId, changedAt);
        }
    }

    /**
     * ponytail: one transaction for every edge. Fine at this scale; an account with tens of
     * thousands of followers would want the unwinding batched by a job.
     */
    @Override
    @Transactional
    public void erase(Long userId, Instant changedAt) {
        // Read before the bulk end below, which is what makes them unreadable.
        List<Long> following = userFollowRepository.findAllFollowingIds(userId);
        List<Long> followers = userFollowRepository.findAllFollowerIds(userId);

        Instant now = Instant.now();
        userFollowRepository.softDeleteAllOf(userId, now);
        // Only the other side's counter: this profile is going, and nothing reads its own.
        following.forEach(followed -> {
            userProfileRepository.decrementFollowerCount(followed);
            userEventPublisher.publishFollowChanged(userId, followed, false);
        });
        followers.forEach(follower -> {
            userProfileRepository.decrementFollowingCount(follower);
            userEventPublisher.publishFollowChanged(follower, userId, false);
        });

        followRequestRepository.softDeleteAllOf(userId, now);
        userBlockRepository.softDeleteAllOf(userId, now);
        userMuteRepository.softDeleteAllOf(userId, now);
        userSettingsRepository.softDeleteByUserId(userId, now);
        // Hidden first, so a profile already gone stays hidden however the events were ordered.
        userProfileRepository.applyAccountState(userId, changedAt, changedAt);
        userProfileRepository.softDeleteByUserId(userId, now);

        log.info("Erased user {}: {} follow(s) and {} follower(s) ended", userId, following.size(), followers.size());
    }
}
