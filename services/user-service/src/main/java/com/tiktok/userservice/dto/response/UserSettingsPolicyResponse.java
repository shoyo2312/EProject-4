package com.tiktok.userservice.dto.response;

import com.tiktok.userservice.entity.SettingsAudience;

/**
 * The settings another service is allowed to know about, for the internal batch endpoint.
 *
 * <p>The two enforcement-relevant fields and nothing else. Nobody outside user-service needs
 * somebody's screen-time preferences, and an internal endpoint that hands them over anyway is a
 * privacy leak waiting for its first careless caller.
 */
public record UserSettingsPolicyResponse(
        Long userId,
        SettingsAudience commentAudience,
        SettingsAudience dmAudience
) {
}
