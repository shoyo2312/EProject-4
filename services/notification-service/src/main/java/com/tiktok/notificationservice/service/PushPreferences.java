package com.tiktok.notificationservice.service;

import com.tiktok.notificationservice.entity.NotificationType;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * What a recipient chose about phone push, from user-service's internal settings: the three
 * Settings → Push notifications → Interactions switches, and sleep hours in the account's zone.
 * Only the push is decided here; the inbox entry is written whatever this says.
 *
 * @param zone null until the web has sent a heartbeat; sleep hours mute nothing without it
 */
public record PushPreferences(boolean likes, boolean comments, boolean newFollowers,
                              LocalTime sleepStart, LocalTime sleepEnd, ZoneId zone) {

    public boolean allows(NotificationType type, Instant now) {
        return switchAllows(type) && !asleep(now);
    }

    /** No default: a new type has to be placed here before this compiles. */
    private boolean switchAllows(NotificationType type) {
        return switch (type) {
            case LIKE, COMMENT_LIKE -> likes;
            case COMMENT -> comments;
            case NEW_FOLLOWER, FOLLOW_REQUEST, FOLLOW_REQUEST_ACCEPTED -> newFollowers;
            // TikTok's Interactions list has no switch for these; reposts have an in-app one only.
            case SHARE, STORY, SYSTEM, REPOST -> true;
            // "Stay updated on your time from your Inbox": the weekly update is inbox only.
            case SCREEN_TIME -> false;
        };
    }

    /** Start included, end excluded; a start after the end is a night that crosses midnight. */
    private boolean asleep(Instant now) {
        if (zone == null || sleepStart == null || sleepEnd == null) {
            return false;
        }
        LocalTime local = now.atZone(zone).toLocalTime();
        return sleepStart.isBefore(sleepEnd)
                ? !local.isBefore(sleepStart) && local.isBefore(sleepEnd)
                : !local.isBefore(sleepStart) || local.isBefore(sleepEnd);
    }
}
