package com.tiktok.analyticsservice.dto.response;

import java.time.LocalDate;

public record DailySignupResponse(
        LocalDate day,
        long signups
) {
}
