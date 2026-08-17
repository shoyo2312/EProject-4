package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getOwnProfile(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(userProfileService.getByUserId(currentUserId, currentUserId));
    }

    @PatchMapping("/me")
    public ApiResponse<UserProfileResponse> updateOwnProfile(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.success(userProfileService.updateOwnProfile(currentUserId, request));
    }

    /**
     * Batch lookup for callers holding a list of user ids and nothing else — the video feed, a
     * comment thread. Written above {@code /{userId}} for readability only; the two never compete,
     * since a bare collection path and a path variable are different mappings.
     *
     * <p>Ids that are missing or blocked are absent from the answer rather than failing it, so the
     * response can be shorter than {@code ids}, and duplicates collapse. Callers key it by
     * {@code userId} rather than counting on a row per id.
     */
    @GetMapping
    public ApiResponse<List<UserProfileResponse>> getProfiles(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam List<Long> ids) {
        return ApiResponse.success(userProfileService.getByUserIds(currentUserId, ids));
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserProfileResponse> getProfile(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(userProfileService.getByUserId(currentUserId, userId));
    }
}
