package com.tiktok.adminservice.dto.response;

import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.ReportTargetType;

import java.time.Instant;

public record ModerationActionResponse(
        Long id,
        Long adminId,
        ModerationActionType actionType,
        ReportTargetType targetType,
        String targetId,
        String reason,
        Long reportId,
        Instant createdAt
) {
}
