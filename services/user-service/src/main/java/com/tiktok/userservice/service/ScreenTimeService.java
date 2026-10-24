package com.tiktok.userservice.service;

import com.tiktok.userservice.dto.response.ScreenTimeWeekResponse;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/** Time on the app, behind Settings → Screen time. See docs/user-settings.md. */
public interface ScreenTimeService {

    /**
     * The most one heartbeat counts. The web sends one a minute; the slack covers a timer that
     * fired late. A client can only inflate its own numbers, so a clamp is all the trust needed.
     */
    int MAX_HEARTBEAT_SECONDS = 120;

    LocalTime DAY_STARTS = LocalTime.of(6, 0);
    LocalTime NIGHT_STARTS = LocalTime.of(22, 0);

    /** Night time is 22:00–06:00 local, as TikTok's tooltip says; the rest is day time. */
    static boolean isNight(LocalTime localTime) {
        return localTime.isBefore(DAY_STARTS) || !localTime.isBefore(NIGHT_STARTS);
    }

    /**
     * Adds {@code seconds} (clamped) to the local date and half of the day that {@code receivedAt}
     * falls on in {@code timeZone}, and stores the zone on the account. An unknown zone is
     * {@code VALIDATION_ERROR} and records nothing.
     */
    void recordHeartbeat(Long userId, int seconds, String timeZone, Instant receivedAt);

    /** Seven days from {@code weekStart}, which must be a Monday. */
    ScreenTimeWeekResponse getWeek(Long userId, LocalDate weekStart);
}
