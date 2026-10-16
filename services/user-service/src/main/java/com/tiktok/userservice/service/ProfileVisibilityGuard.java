package com.tiktok.userservice.service;

import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * A block hides both sides from each other, so every read of another user's profile data goes
 * through here. Blocking already severs the follow edges (see BlockServiceImpl.block), and this
 * closes the matching read path: without it a blocked user could still enumerate the blocker's
 * whole social graph via /followers.
 *
 * <p>The failure is reported as the plain "profile not found" 404 rather than a 403: a distinct
 * status would itself confirm that the target exists and has blocked the caller.
 */
@Component
@RequiredArgsConstructor
public class ProfileVisibilityGuard {

    private final UserBlockRepository userBlockRepository;

    /** A null viewer is a signed-out reader: they block nobody and nobody blocks them. */
    public void requireVisible(Long viewerId, Long targetUserId) {
        if (viewerId == null || viewerId.equals(targetUserId)) {
            return;
        }
        if (userBlockRepository.existsBlockBetween(viewerId, targetUserId)) {
            throw new UserProfileNotFoundException(targetUserId);
        }
    }
}
