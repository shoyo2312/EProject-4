package com.tiktok.analyticsservice.dto.response;

import java.time.LocalDate;

public record DailyCountResponse(
        LocalDate day,
        String eventType,
        long count
) {
}
