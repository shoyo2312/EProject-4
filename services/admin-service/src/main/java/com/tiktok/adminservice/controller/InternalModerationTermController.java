package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.dto.response.ModerationTermSnapshotResponse;
import com.tiktok.adminservice.service.ModerationTermService;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service: the admin term list, polled by every service that screens text (moderation-lib's
 * {@code AdminTermsSync}). Internal token, not a user's — {@code SecurityConfig} — and denied at the
 * gateway, like every other {@code /internal/} prefix.
 */
@RestController
@RequestMapping("/api/v1/admin/internal")
@RequiredArgsConstructor
public class InternalModerationTermController {

    private final ModerationTermService moderationTermService;

    @GetMapping("/blocklist")
    public ApiResponse<ModerationTermSnapshotResponse> blocklist() {
        return ApiResponse.success(moderationTermService.snapshot());
    }
}
