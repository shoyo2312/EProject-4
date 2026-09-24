package com.tiktok.userservice.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.entity.SettingsAudience;

import java.time.LocalTime;

/**
 * Every setting the owner can change, as a flat keyed object. Deliberately not the section/group
 * tree the settings page draws: layout is presentation and belongs to the client, and a server
 * that ships the layout needs a deploy to move a row.
 *
 * <p>An account with no stored row gets this filled from {@link
 * com.tiktok.userservice.entity.UserSettings#defaultsFor}, so a caller never has to handle "no
 * settings yet".
 */
public record UserSettingsResponse(
        SettingsAudience commentAudience,
        SettingsAudience dmAudience,
        /** Null is off, not "unset" — see V10. */
        Integer screenTimeLimitMinutes,
        @JsonFormat(pattern = UpdateUserSettingsRequest.TIME_FORMAT) LocalTime sleepHoursStart,
        @JsonFormat(pattern = UpdateUserSettingsRequest.TIME_FORMAT) LocalTime sleepHoursEnd,
        boolean weeklyScreenTimeDigest
) {
}
