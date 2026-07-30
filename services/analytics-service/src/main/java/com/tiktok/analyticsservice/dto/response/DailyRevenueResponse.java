package com.tiktok.analyticsservice.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyRevenueResponse(
        LocalDate day,
        long ordersCreated,
        long paymentsCompleted,
        BigDecimal revenue
) {
}
