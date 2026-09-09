package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.dto.request.ModerationRequest;
import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.service.AdminService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Takedown and restore straight from the console, without a report behind them. The same pair is
 * also reachable by resolving a report; both land in the same audit table and publish the same
 * event, so video-service cannot tell — and does not need to tell — which route an action took.
 *
 * <p>The video id is a string: video-service stores documents in Mongo, so ids are not the
 * Snowflake longs the rest of the platform uses.
 */
@RestController
@RequestMapping("/api/v1/admin/videos")
@RequiredArgsConstructor
public class VideoModerationController {

    private final AdminService adminService;

    @PostMapping("/{videoId}/takedown")
    public ApiResponse<ModerationActionResponse> takedown(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable String videoId,
            @Valid @RequestBody ModerationRequest request) {
        return ApiResponse.success(adminService.moderate(currentAdminId, ReportTargetType.VIDEO,
                videoId, ModerationActionType.TAKEDOWN_VIDEO, request.reason()));
    }

    @PostMapping("/{videoId}/restore")
    public ApiResponse<ModerationActionResponse> restore(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable String videoId,
            @Valid @RequestBody ModerationRequest request) {
        return ApiResponse.success(adminService.moderate(currentAdminId, ReportTargetType.VIDEO,
                videoId, ModerationActionType.RESTORE_VIDEO, request.reason()));
    }
}
