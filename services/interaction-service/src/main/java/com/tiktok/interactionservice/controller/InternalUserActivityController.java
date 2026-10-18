package com.tiktok.interactionservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.interactionservice.dto.response.ActivityPageResponse;
import com.tiktok.interactionservice.service.UserActivityExport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service: one user's likes, saves and reposts, newest first, for user-service's
 * "Download your data" job, which runs with no signed-in user to forward. Same tokenless prefix
 * as {@link InternalCommentController} — denied at api-gateway, token-checked here.
 */
@RestController
@RequestMapping("/api/v1/interactions/internal/users/{userId}")
@RequiredArgsConstructor
public class InternalUserActivityController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserActivityExport userActivityExport;

    @GetMapping("/likes")
    public ApiResponse<ActivityPageResponse> likes(@PathVariable Long userId,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(userActivityExport.likes(userId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    @GetMapping("/saves")
    public ApiResponse<ActivityPageResponse> saves(@PathVariable Long userId,
                                                   @RequestParam(required = false) String cursor,
                                                   @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(userActivityExport.saves(userId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    @GetMapping("/reposts")
    public ApiResponse<ActivityPageResponse> reposts(@PathVariable Long userId,
                                                     @RequestParam(required = false) String cursor,
                                                     @RequestParam(defaultValue = "100") int size) {
        return ApiResponse.success(userActivityExport.reposts(userId, cursor, Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }
}
