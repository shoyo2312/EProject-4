package com.tiktok.adminservice.dto.response;

public record StatsSummaryResponse(
        long pendingReports,
        long resolvedReports,
        long dismissedReports,
        long actionsLast24h
) {
}
