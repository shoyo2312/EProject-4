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
 * Banning straight from the user directory, without a report to resolve first. The audit trail
 * and the Kafka event are identical either way — {@code reportId} is simply null.
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class UserModerationController {

    private final AdminService adminService;

    @PostMapping("/{userId}/ban")
    public ApiResponse<ModerationActionResponse> ban(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable Long userId,
            @Valid @RequestBody ModerationRequest request) {
        return ApiResponse.success(adminService.moderate(currentAdminId, ReportTargetType.USER,
                String.valueOf(userId), ModerationActionType.BAN_USER, request.reason()));
    }

    @PostMapping("/{userId}/unban")
    public ApiResponse<ModerationActionResponse> unban(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable Long userId,
            @Valid @RequestBody ModerationRequest request) {
        return ApiResponse.success(adminService.moderate(currentAdminId, ReportTargetType.USER,
                String.valueOf(userId), ModerationActionType.UNBAN_USER, request.reason()));
    }
}
