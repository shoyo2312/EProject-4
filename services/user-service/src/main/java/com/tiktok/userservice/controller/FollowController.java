package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.FollowService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/{userId}")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    @PostMapping("/follow")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FollowResponse> follow(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(followService.follow(currentUserId, userId));
    }

    @DeleteMapping("/follow")
    public ApiResponse<Void> unfollow(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        followService.unfollow(currentUserId, userId);
        return ApiResponse.success(null);
    }

    @GetMapping("/friendship")
    public ApiResponse<FriendshipResponse> friendship(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(followService.friendship(currentUserId, userId));
    }

    @GetMapping("/followers")
    public ApiResponse<Page<UserProfileResponse>> listFollowers(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId,
            Pageable pageable) {
        return ApiResponse.success(followService.listFollowers(currentUserId, userId, pageable));
    }

    @GetMapping("/following")
    public ApiResponse<Page<UserProfileResponse>> listFollowing(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId,
            Pageable pageable) {
        return ApiResponse.success(followService.listFollowing(currentUserId, userId, pageable));
    }
}
