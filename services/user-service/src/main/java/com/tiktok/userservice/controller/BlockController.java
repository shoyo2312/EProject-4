package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.response.BlockResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.BlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Tag(name = "Blocks", description = "Hiding two accounts from each other")
public class BlockController {

    private final BlockService blockService;

    @Operation(summary = "Block a user",
            description = "Severs the follow edges both ways. 400 CANNOT_BLOCK_SELF, "
                    + "409 ALREADY_BLOCKED.")
    @PostMapping("/api/v1/users/{userId}/block")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BlockResponse> block(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        return ApiResponse.success(blockService.block(currentUserId, userId));
    }

    @Operation(summary = "Unblock a user",
            description = "The follows are not restored. 404 NOT_BLOCKED.")
    @DeleteMapping("/api/v1/users/{userId}/block")
    public ApiResponse<Void> unblock(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable Long userId) {
        blockService.unblock(currentUserId, userId);
        return ApiResponse.success(null);
    }

    @Operation(summary = "Accounts the caller has blocked")
    @GetMapping("/api/v1/users/me/blocked")
    public ApiResponse<Page<UserProfileResponse>> listBlocked(
            @AuthenticationPrincipal Long currentUserId,
            Pageable pageable) {
        return ApiResponse.success(blockService.listBlocked(currentUserId, pageable));
    }
}
