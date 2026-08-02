package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.MuteResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.MuteService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class MuteController {

    private final MuteService muteService;

    @PostMapping("/api/v1/users/{userId}/mute")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MuteResponse> mute(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(muteService.mute(currentUserId, userId));
    }

    @DeleteMapping("/api/v1/users/{userId}/mute")
    public ApiResponse<Void> unmute(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        muteService.unmute(currentUserId, userId);
        return ApiResponse.success(null);
    }

    @GetMapping("/api/v1/users/me/muted")
    public ApiResponse<Page<UserProfileResponse>> listMuted(
            @AuthenticationPrincipal Long currentUserId,
            Pageable pageable) {
        return ApiResponse.success(muteService.listMuted(currentUserId, pageable));
    }
}
