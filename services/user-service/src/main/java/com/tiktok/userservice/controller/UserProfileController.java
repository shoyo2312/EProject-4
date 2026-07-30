package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getOwnProfile(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(userProfileService.getByUserId(currentUserId));
    }

    @PatchMapping("/me")
    public ApiResponse<UserProfileResponse> updateOwnProfile(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.success(userProfileService.updateOwnProfile(currentUserId, request));
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserProfileResponse> getProfile(@PathVariable Long userId) {
        return ApiResponse.success(userProfileService.getByUserId(userId));
    }
}
