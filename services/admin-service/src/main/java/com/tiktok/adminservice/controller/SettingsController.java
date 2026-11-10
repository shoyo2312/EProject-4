package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.client.ModerationSettingsClient;
import com.tiktok.adminservice.client.ModerationSettingsClient.ModerationSettings;
import com.tiktok.adminservice.dto.response.TextModerationStatusResponse;
import com.tiktok.adminservice.service.SystemStatusService;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The settings the console shows but does not own. Read-only, and it stays that way while the
 * values come from another service's environment — see {@link ModerationSettingsClient}.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final ModerationSettingsClient moderationSettingsClient;
    private final SystemStatusService systemStatusService;

    @GetMapping("/moderation")
    public ApiResponse<ModerationSettings> moderation() {
        return ApiResponse.success(moderationSettingsClient.fetch());
    }

    /** TEXT_MODERATION_ENFORCE and TEXT_CLASSIFIER_ENFORCE as each service that screens text runs with them. */
    @GetMapping("/text-moderation")
    public ApiResponse<List<TextModerationStatusResponse>> textModeration() {
        return ApiResponse.success(systemStatusService.textModeration());
    }
}
