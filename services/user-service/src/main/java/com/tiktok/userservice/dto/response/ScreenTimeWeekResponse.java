package com.tiktok.userservice.dto.response;

import java.time.LocalDate;
import java.util.List;

/** One Monday-to-Sunday week of time on the app. Always seven days, Monday first, zero-filled. */
public record ScreenTimeWeekResponse(LocalDate weekStart, List<ScreenTimeDayResponse> days) {
}
