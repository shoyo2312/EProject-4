package com.tiktok.userservice.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.entity.SettingsAudience;

import java.time.LocalTime;
import java.util.List;

/**
 * The settings another service is allowed to know about, for the internal batch endpoint.
 *
 * <p>The ones another service enforces and nothing else:
 * <ul>
 *   <li>the two audiences;</li>
 *   <li>the comment filters interaction-service applies (relayed by video-service's {@code /policy});</li>
 *   <li>what notification-service checks before a phone push: the three push switches, sleep hours
 *       and the time zone they are read in.</li>
 * </ul>
 * The daily limit, its passcode and the in-app switches stay in user-service: nobody else enforces
 * them, and an internal endpoint that hands them over anyway is a privacy leak waiting for its
 * first careless caller.
 */
public record UserSettingsPolicyResponse(
        Long userId,
        SettingsAudience commentAudience,
        SettingsAudience dmAudience,
        boolean filterSpamOffensive,
        boolean filterAllComments,
        List<String> commentKeywords,
        boolean pushLikes,
        boolean pushComments,
        boolean pushNewFollowers,
        @JsonFormat(pattern = UpdateUserSettingsRequest.TIME_FORMAT) LocalTime sleepHoursStart,
        @JsonFormat(pattern = UpdateUserSettingsRequest.TIME_FORMAT) LocalTime sleepHoursEnd,
        /** Null until a heartbeat has sent one; sleep hours mute nothing without it. */
        String timeZone
) {
}
