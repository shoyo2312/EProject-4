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

        return new FollowResponse(followerId, followingId);
    }

    @Override
    @Transactional
    public void unfollow(Long followerId, Long followingId) {
        UserFollow follow = userFollowRepository.findByFollowerIdAndFollowingIdAndDeletedAtIsNull(followerId, followingId)
                .orElseThrow(NotFollowingException::new);

        follow.markDeleted();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowers(Long userId, Pageable pageable) {
        return userFollowRepository.findByFollowingIdAndDeletedAtIsNull(userId, pageable)
                .map(UserFollow::getFollowerId)
                .map(this::toProfileResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UserProfileResponse> listFollowing(Long userId, Pageable pageable) {
        return userFollowRepository.findByFollowerIdAndDeletedAtIsNull(userId, pageable)
                .map(UserFollow::getFollowingId)
                .map(this::toProfileResponse);
    }

    private UserProfileResponse toProfileResponse(Long userId) {
        UserProfile profile = userProfileRepository.findByUserIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserProfileNotFoundException(userId));

        UserProfileResponse base = userProfileMapper.toResponse(profile);
        long followerCount = userFollowRepository.countByFollowingIdAndDeletedAtIsNull(userId);
        long followingCount = userFollowRepository.countByFollowerIdAndDeletedAtIsNull(userId);

        return new UserProfileResponse(
                base.userId(),
                base.displayName(),
                base.bio(),
                base.avatarUrl(),
                followerCount,
                followingCount);
    }
}
