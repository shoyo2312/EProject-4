package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.MuteResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.MuteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Tag(name = "Mutes", description = "Dropping an account out of the caller's feed without blocking it")
public class MuteController {

    private final MuteService muteService;

    @Operation(summary = "Mute a user",
            description = "One-directional and invisible to the other side. 400 CANNOT_MUTE_SELF, "
                    + "409 ALREADY_MUTED.")
    @PostMapping("/api/v1/users/{userId}/mute")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MuteResponse> mute(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(muteService.mute(currentUserId, userId));
    }

    @Operation(summary = "Unmute a user", description = "404 NOT_MUTED.")
    @DeleteMapping("/api/v1/users/{userId}/mute")
    public ApiResponse<Void> unmute(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        muteService.unmute(currentUserId, userId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Accounts the caller has muted")
    @GetMapping("/api/v1/users/me/muted")
    public ApiResponse<Page<UserProfileResponse>> listMuted(
            @AuthenticationPrincipal Long currentUserId,
            Pageable pageable) {
        return ApiResponse.success(muteService.listMuted(currentUserId, pageable));
    }

    /**
     * Service-to-service: recommendation-service drops muted accounts out of the feed and has no
     * read path into this database. No token — the gateway denies the internal prefix, so only
     * the internal network reaches it, same as the block check.
     */
    @Operation(summary = "Internal: accounts a user currently mutes")
    @GetMapping("/api/v1/users/internal/{userId}/muted-ids")
    public ApiResponse<List<Long>> mutedIds(@PathVariable Long userId) {
        return ApiResponse.success(muteService.mutedIds(userId));
    }
}
