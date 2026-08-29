package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FollowService {

    FollowResponse follow(Long followerId, Long followingId);

    void unfollow(Long followerId, Long followingId);

    /** Whether {@code viewerId} and {@code otherUserId} follow each other (mutual = "friends"). */
    FriendshipResponse friendship(Long viewerId, Long otherUserId);

    Page<UserProfileResponse> listFollowers(Long viewerId, Long userId, Pageable pageable);

    Page<UserProfileResponse> listFollowing(Long viewerId, Long userId, Pageable pageable);
}
