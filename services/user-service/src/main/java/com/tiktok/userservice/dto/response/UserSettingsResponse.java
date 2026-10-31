package com.tiktok.userservice.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.entity.SettingsAudience;

import java.time.LocalTime;
import java.util.List;

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
        boolean weeklyScreenTimeDigest,
        /**
         * Read off the profile rather than the settings row, because every viewer needs it there —
         * see V11. False until the profile exists.
         */
        boolean privateAccount,
        /** False: comments on this account's videos are not held for profanity, insults or spam. */
        boolean filterSpamOffensive,
        /** Every comment from someone else waits for approval. */
        boolean filterAllComments,
        /** A comment containing one of these waits for approval. */
        List<String> commentKeywords,
        /** Push notifications → Interactions: phone push and desktop notifications. */
        boolean pushLikes,
        boolean pushComments,
        boolean pushNewFollowers,
        /** Push notifications → In-app notifications: the banner. */
        boolean inAppLikes,
        boolean inAppComments,
        boolean inAppNewFollowers,
        boolean inAppReposts,
        /** The IANA zone the last screen-time heartbeat came from. Null until there has been one. */
        String timeZone,
        /** One daily limit per weekday, Monday first; null unless that is the shape chosen. */
        List<Integer> screenTimeLimitsByDay,
        /** The passcode itself is write-only; this says whether one is set. */
        boolean hasScreenTimePasscode
) {
}
