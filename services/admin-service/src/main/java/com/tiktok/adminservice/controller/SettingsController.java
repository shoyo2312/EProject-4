package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.client.ModerationSettingsClient;
import com.tiktok.adminservice.client.ModerationSettingsClient.ModerationSettings;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The settings the console shows but does not own. Read-only, and it stays that way while the
 * values come from another service's environment — see {@link ModerationSettingsClient}.
 */
@RestController
@RequestMapping("/api/v1/admin/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final ModerationSettingsClient moderationSettingsClient;

    @GetMapping("/moderation")
    public ApiResponse<ModerationSettings> moderation() {
        return ApiResponse.success(moderationSettingsClient.fetch());
    }
}
