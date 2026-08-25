package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.AvatarUploadService;
import com.tiktok.userservice.service.UserProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final AvatarUploadService avatarUploadService;

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
     * The only way a client can set an avatar. It uploads the file itself rather than a URL,
     * because {@code PATCH /me} accepts nothing a client could have invented — see
     * {@link AvatarUploadService}.
     *
     * <p>Answers the whole profile, not just the URL, so the caller refreshes from one response
     * exactly as it does after a {@code PATCH}.
     */
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserProfileResponse> uploadOwnAvatar(
            @AuthenticationPrincipal Long currentUserId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(avatarUploadService.replaceOwnAvatar(currentUserId, file));
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
    /**
     * Handle or display-name search. Separate path from the batch {@code GET /users?ids=} above
     * because it is a different question — that one hydrates ids a caller already has.
     */
    @GetMapping("/search")
    public ApiResponse<Page<UserProfileResponse>> search(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(required = false) String q,
            Pageable pageable) {
        return ApiResponse.success(userProfileService.search(currentUserId, q, pageable));
    }

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
