package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
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
import org.springframework.dao.DataIntegrityViolationException;
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

        // The follower is checked for the same reason as the target, on the other side of the
        // edge. A caller whose UserRegisteredEvent never landed holds a valid token but no
        // profile row; incrementFollowingCount would match nothing and the edge would put an id
        // with no profile into the target's followers list forever.
        if (!userProfileRepository.existsByUserIdAndDeletedAtIsNull(followerId)) {
            throw new UserProfileNotFoundException(followerId);
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

        // The check above is not the guarantee — two concurrent requests both pass it and only
        // uq_user_follows_follower_following stops the second. saveAndFlush forces that violation
        // to surface here instead of at commit, so the loser is answered 409 ALREADY_FOLLOWING
        // like the sequential case rather than a 500, and rolls back before the counters move.
        try {
            userFollowRepository.saveAndFlush(follow);
        } catch (DataIntegrityViolationException ex) {
            throw new AlreadyFollowingException();
        }

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
    public FriendshipResponse friendship(Long viewerId, Long otherUserId) {
        // Read-only and edge-only: a non-existent user simply has no follow rows, so it answers
        // false rather than 404 — the caller (video-service's FRIENDS visibility check) only ever
        // needs the boolean, and self is never a friend.
        boolean friends = !viewerId.equals(otherUserId)
                && userFollowRepository
                        .findByFollowerIdAndFollowingIdAndDeletedAtIsNull(viewerId, otherUserId).isPresent()
                && userFollowRepository
                        .findByFollowerIdAndFollowingIdAndDeletedAtIsNull(otherUserId, viewerId).isPresent();
        return new FriendshipResponse(otherUserId, friends);
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
