package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/{userId}")
@RequiredArgsConstructor
@Tag(name = "Follows", description = "The follow graph and what a viewer may see of it")
public class FollowController {

    private final FollowService followService;

    @Operation(summary = "Follow a user",
            description = "400 CANNOT_FOLLOW_SELF, 404 USER_PROFILE_NOT_FOUND when either side "
                    + "has no profile, 409 ALREADY_FOLLOWING, 409 CANNOT_FOLLOW_BLOCKED_USER "
                    + "when a block exists in either direction.")
    @PostMapping("/follow")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<FollowResponse> follow(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(followService.follow(currentUserId, userId));
    }

    @Operation(summary = "Unfollow a user", description = "404 NOT_FOLLOWING when there is no edge.")
    @DeleteMapping("/follow")
    public ApiResponse<Void> unfollow(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        followService.unfollow(currentUserId, userId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Whether the viewer and this user follow each other",
            description = "Edge-only: an id with no account is simply not a friend, not a 404.")
    @GetMapping("/friendship")
    public ApiResponse<FriendshipResponse> friendship(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(followService.friendship(currentUserId, userId));
    }

    @Operation(summary = "Who follows this user",
            description = "404 USER_PROFILE_NOT_FOUND if a block hides the account from the "
                    + "viewer. Accounts that blocked the viewer are absent from the page.")
    @GetMapping("/followers")
    public ApiResponse<Page<UserProfileResponse>> listFollowers(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId,
            Pageable pageable) {
        return ApiResponse.success(followService.listFollowers(currentUserId, userId, pageable));
    }

    @Operation(summary = "Who this user follows", description = "Same visibility rules as /followers.")
    @GetMapping("/following")
    public ApiResponse<Page<UserProfileResponse>> listFollowing(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId,
            Pageable pageable) {
        return ApiResponse.success(followService.listFollowing(currentUserId, userId, pageable));
    }
}
