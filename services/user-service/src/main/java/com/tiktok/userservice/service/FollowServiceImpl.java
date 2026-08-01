package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.entity.UserFollow;
import com.tiktok.userservice.entity.UserProfile;
import com.tiktok.userservice.exception.AlreadyFollowingException;
import com.tiktok.userservice.exception.CannotFollowSelfException;
import com.tiktok.userservice.exception.NotFollowingException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.mapper.UserProfileMapper;
import com.tiktok.userservice.repository.UserFollowRepository;
import com.tiktok.userservice.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FollowServiceImpl implements FollowService {

    private final UserFollowRepository userFollowRepository;
    private final UserProfileRepository userProfileRepository;
    private final UserProfileMapper userProfileMapper;

    @Override
    @Transactional
    public FollowResponse follow(Long followerId, Long followingId) {
        if (followerId.equals(followingId)) {
            throw new CannotFollowSelfException();
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
    public Page<UserProfileResponse> listFollowers(Long userId, Pageable pageable) {
        Page<Long> followerIds = userFollowRepository.findByFollowingIdAndDeletedAtIsNull(userId, pageable)
                .map(UserFollow::getFollowerId);
        return toProfileResponses(followerIds);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowing(Long userId, Pageable pageable) {
        Page<Long> followingIds = userFollowRepository.findByFollowerIdAndDeletedAtIsNull(userId, pageable)
                .map(UserFollow::getFollowingId);
        return toProfileResponses(followingIds);
    }

    /**
     * Batches the profile lookup for a page of user ids into a single query; follower/following
     * counts come straight off UserProfile (denormalized columns kept in sync by follow/unfollow),
     * so no separate count query is needed at all.
     */
    private Page<UserProfileResponse> toProfileResponses(Page<Long> userIdsPage) {
        List<Long> userIds = userIdsPage.getContent();
        if (userIds.isEmpty()) {
            return userIdsPage.map(id -> null);
        }

        Map<Long, UserProfile> profilesByUserId = userProfileRepository.findByUserIdInAndDeletedAtIsNull(userIds).stream()
                .collect(Collectors.toMap(UserProfile::getUserId, Function.identity()));

        return userIdsPage.map(userId -> {
            UserProfile profile = profilesByUserId.get(userId);
            if (profile == null) {
                throw new UserProfileNotFoundException(userId);
            }
            return userProfileMapper.toResponse(profile);
        });
    }
}
