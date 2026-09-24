package com.tiktok.userservice.controller;

import com.tiktok.common.response.ApiResponse;
import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.service.UserSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Tag(name = "User settings", description = "Account-level preferences behind the /setting page")
public class UserSettingsController {

    private final UserSettingsService userSettingsService;

    @Operation(summary = "The caller's own settings",
            description = "Never 404: an account that has never changed a setting reads the "
                    + "defaults, and the read does not create a row.")
    @GetMapping("/me/settings")
    public ApiResponse<UserSettingsResponse> getOwnSettings(@AuthenticationPrincipal Long currentUserId) {
        return ApiResponse.success(userSettingsService.getOwnSettings(currentUserId));
    }

    @Operation(summary = "Change the caller's settings",
            description = "True partial update: an absent key is left alone, and an explicit null "
                    + "clears one of the three nullable fields. Unlike PATCH /me there is no "
                    + "empty-string-clears rule. 400 VALIDATION_ERROR, 409 CONCURRENT_MODIFICATION.")
    @PatchMapping("/me/settings")
    public ApiResponse<UserSettingsResponse> updateOwnSettings(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody UpdateUserSettingsRequest request) {
        return ApiResponse.success(userSettingsService.updateOwnSettings(currentUserId, request));
    }

    /**
     * Service-to-service: video-service resolves a video owner's comment audience for
     * {@code /videos/{id}/policy}, and chat-service a recipient's DM audience when a conversation
     * opens. No token — chat asks from WebSocket frames, where there is none to forward — so this
     * prefix is permitted here and denied at the gateway, exactly like the block and mute checks.
     *
     * <p>Every id asked for is answered, with the defaults where there is no row. A caller that
     * could not tell "no answer" from "no restriction" would eventually read one as the other.
     */
    @Operation(summary = "Internal: the enforcement-relevant settings for up to 100 ids",
            description = "Ids with no stored settings come back carrying the defaults rather "
                    + "than being omitted. More ids than the cap is 400 TOO_MANY_PROFILE_IDS.")
    @GetMapping("/internal/settings")
    public ApiResponse<List<UserSettingsPolicyResponse>> settingsPolicies(@RequestParam List<Long> ids) {
        return ApiResponse.success(userSettingsService.getSettingsPolicies(ids));
    }
}
