package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FollowService {

    FollowResponse follow(Long followerId, Long followingId);

    void unfollow(Long followerId, Long followingId);

    Page<UserProfileResponse> listFollowers(Long userId, Pageable pageable);

    Page<UserProfileResponse> listFollowing(Long userId, Pageable pageable);
}
