package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.exception.AlreadyFollowingException;
import com.tiktok.userservice.exception.CannotFollowBlockedUserException;
import com.tiktok.userservice.exception.CannotFollowSelfException;
import com.tiktok.userservice.exception.NotFollowingException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.repository.UserBlockRepository;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserProfileBatchAssembler profileBatchAssembler;
    private final ProfileVisibilityGuard profileVisibilityGuard;

    @Override
    @Transactional
    public FollowResponse follow(Long followerId, Long followingId) {
        if (followerId.equals(followingId)) {
            throw new CannotFollowSelfException();
        }

        // No FK backs user_follows.following_id, and the counter updates are UPDATE ... WHERE
        // that silently match zero rows — without this an edge to a non-existent user is
        // accepted with a 200 and leaves followingCount permanently overstated.
        if (!userProfileRepository.existsByUserIdAndDeletedAtIsNull(followingId)) {
            throw new UserProfileNotFoundException(followingId);
        }

        if (userBlockRepository.existsBlockBetween(followerId, followingId)) {
            throw new CannotFollowBlockedUserException();
        }

        if (userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(followerId, followingId).isPresent()) {
            throw new AlreadyFollowingException();
        }

        UserFollow follow = UserFollow.builder()
                .followerId(followerId)
                .followingId(followingId)
                .build();

        userFollowRepository.save(follow);
        userProfileRepository.incrementFollowingCount(followerId);
        userProfileRepository.incrementFollowerCount(followingId);

        return new FollowResponse(followerId, followingId);
    }

    @Override
    @Transactional
    public void unfollow(Long followerId, Long followingId) {
        UserFollow follow = userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(followerId, followingId)
                .orElseThrow(NotFollowingException::new);

        follow.markDeleted();
        userProfileRepository.decrementFollowingCount(followerId);
        userProfileRepository.decrementFollowerCount(followingId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowers(Long viewerId, Long userId, Pageable pageable) {
        profileVisibilityGuard.requireVisible(viewerId, userId);

        Page<Long> followerIds = userFollowRepository.findByFollowingIdAndDeletedAtIsNull(userId, pageable)
                .map(UserFollow::getFollowerId);
        return profileBatchAssembler.toResponses(followerIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowing(Long viewerId, Long userId, Pageable pageable) {
        profileVisibilityGuard.requireVisible(viewerId, userId);

        Page<Long> followingIds = userFollowRepository.findByFollowerIdAndDeletedAtIsNull(userId, pageable)
                .map(UserFollow::getFollowingId);
        return profileBatchAssembler.toResponses(followingIds);
    }
}
