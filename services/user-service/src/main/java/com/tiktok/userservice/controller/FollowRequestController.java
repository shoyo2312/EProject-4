package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.FollowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A private account's side of follow requests. The requester's side is POST/DELETE /{userId}/follow. */
@RestController
@RequestMapping("/api/v1/users/me/follow-requests")
@RequiredArgsConstructor
@Tag(name = "Follows", description = "The follow graph and what a viewer may see of it")
public class FollowRequestController {

    private final FollowService followService;

    @Operation(summary = "Pending follow requests to the caller",
            description = "Newest first. A request ends when it is approved, rejected or "
                    + "withdrawn, or when either side blocks the other.")
    @GetMapping
    public ApiResponse<Page<UserProfileResponse>> list(
            @AuthenticationPrincipal Long currentUserId,
            Pageable pageable) {
        return ApiResponse.success(followService.listFollowRequests(currentUserId, pageable));
    }

    @Operation(summary = "Approve a follow request",
            description = "The requester now follows the caller. 404 FOLLOW_REQUEST_NOT_FOUND "
                    + "when none is pending.")
    @PostMapping("/{requesterId}/approve")
    public ApiResponse<FollowResponse> approve(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long requesterId) {
        return ApiResponse.success(followService.approveFollowRequest(currentUserId, requesterId));
    }

    @Operation(summary = "Reject a follow request",
            description = "Silent: the requester is not told. 404 FOLLOW_REQUEST_NOT_FOUND when "
                    + "none is pending.")
    @DeleteMapping("/{requesterId}")
    public ApiResponse<Void> reject(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long requesterId) {
        followService.rejectFollowRequest(currentUserId, requesterId);
        return ApiResponse.success(null);
    }
}
