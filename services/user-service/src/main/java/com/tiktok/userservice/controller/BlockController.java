package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.BlockResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.BlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class BlockController {

    private final BlockService blockService;

    @PostMapping("/api/v1/users/{userId}/block")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BlockResponse> block(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(blockService.block(currentUserId, userId));
    }

    @DeleteMapping("/api/v1/users/{userId}/block")
    public ApiResponse<Void> unblock(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        blockService.unblock(currentUserId, userId);
        return ApiResponse.success(null);
    }

    @GetMapping("/api/v1/users/me/blocked")
    public ApiResponse<Page<UserProfileResponse>> listBlocked(
            @AuthenticationPrincipal Long currentUserId,
            Pageable pageable) {
        return ApiResponse.success(blockService.listBlocked(currentUserId, pageable));
    }
}
