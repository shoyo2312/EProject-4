package com.tiktok.adminservice.dto.response;

import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;

import java.time.Instant;

public record ReportResponse(
        Long id,
        Long reporterId,
        ReportTargetType targetType,
        String targetId,
        String reason,
        ReportStatus status,
        Long resolvedBy,
        Instant resolvedAt,
        Instant createdAt
) {
}
