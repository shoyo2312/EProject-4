package com.tiktok.userservice.dto.response;

import com.tiktok.userservice.entity.SettingsAudience;

import java.util.List;

/**
 * The settings another service is allowed to know about, for the internal batch endpoint.
 *
 * <p>The ones another service enforces and nothing else: the two audiences, and the comment filters
 * interaction-service applies (relayed by video-service's {@code /policy}). Nobody outside
 * user-service needs somebody's screen-time preferences, and an internal endpoint that hands them
 * over anyway is a privacy leak waiting for its first careless caller.
 */
public record UserSettingsPolicyResponse(
        Long userId,
        SettingsAudience commentAudience,
        SettingsAudience dmAudience,
        boolean filterSpamOffensive,
        boolean filterAllComments,
        List<String> commentKeywords
) {
}
