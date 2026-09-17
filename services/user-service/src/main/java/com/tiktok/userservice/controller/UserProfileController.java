package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.AvatarUploadService;
import com.tiktok.userservice.service.UserProfileService;
import jakarta.validation.Valid;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "User profiles", description = "Reading and editing profiles")
public class UserProfileController {

    private final UserProfileService userProfileService;
    private final AvatarUploadService avatarUploadService;

    @Operation(summary = "The caller's own profile")
    @GetMapping("/me")
    public ApiResponse<UserProfileResponse> getOwnProfile(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(userProfileService.getByUserId(currentUserId, currentUserId));
    }

    @Operation(summary = "Edit the caller's profile",
            description = "A null field is left unchanged; an empty string clears an optional "
                    + "one. avatarUrl only accepts a host on the media allow-list, so in practice "
                    + "it is a URL this API handed out — POST /me/avatar is how a new picture gets set.")
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
    @Operation(summary = "Upload the caller's avatar",
            description = "JPEG, PNG, or WebP up to the configured size; answers the whole "
                    + "updated profile. 400 INVALID_AVATAR on anything else, 413 INVALID_AVATAR "
                    + "past the multipart limit.")
    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<UserProfileResponse> uploadOwnAvatar(
            @AuthenticationPrincipal Long currentUserId,
            @RequestPart("file") MultipartFile file) {
        return ApiResponse.success(avatarUploadService.replaceOwnAvatar(currentUserId, file));
    }

    /**
     * Handle or display-name search. Separate path from the batch {@code GET /users?ids=} above
     * because it is a different question — that one hydrates ids a caller already has.
     */
    @Operation(summary = "Search profiles by handle or display name",
            description = "Ordered by follower count. A blank q is an empty page, not everyone. "
                    + "Blocked accounts are excluded by the query, so the page total is reachable.")
    @GetMapping("/search")
    public ApiResponse<Page<UserProfileResponse>> search(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(required = false) String q,
            Pageable pageable) {
        return ApiResponse.success(userProfileService.search(currentUserId, q, pageable));
    }

    /**
     * Batch lookup for callers holding a list of user ids and nothing else — the video feed, a
     * comment thread. Written below {@code /search} but above {@code /{userId}} for readability
     * only; the three never compete, since a literal segment, a bare collection path and a path
     * variable are different mappings.
     */
    @Operation(summary = "Hydrate up to 100 user ids into profiles",
            description = "Missing and blocked ids are absent from the answer rather than "
                    + "failing it, so the list can be shorter than ids and duplicates collapse — "
                    + "key it by userId. More ids than the cap is 400 TOO_MANY_PROFILE_IDS.")
    @GetMapping
    public ApiResponse<List<UserProfileResponse>> getProfiles(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam List<Long> ids) {
        return ApiResponse.success(userProfileService.getByUserIds(currentUserId, ids));
    }

    @Operation(summary = "One user's profile",
            description = "404 USER_PROFILE_NOT_FOUND, which is also the answer when a block "
                    + "hides the account — a distinct status would confirm the block.")
    @GetMapping("/{userId}")
    public ApiResponse<UserProfileResponse> getProfile(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(userProfileService.getByUserId(currentUserId, userId));
    }
}
