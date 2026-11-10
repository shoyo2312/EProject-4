package com.tiktok.userservice.dto.response;

import java.time.LocalDate;

/**
 * @param daySeconds   06:00–22:00 local
 * @param nightSeconds 22:00–06:00 local
 */
public record ScreenTimeDayResponse(LocalDate date, int daySeconds, int nightSeconds) {
}
