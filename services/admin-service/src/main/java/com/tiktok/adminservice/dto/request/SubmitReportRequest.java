package com.tiktok.adminservice.dto.request;

import com.tiktok.adminservice.entity.ReportTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitReportRequest(
        @NotNull ReportTargetType targetType,
        // The column's width. Past it the insert failed on a constraint and was answered as a
        // duplicate report.
        @NotBlank @Size(max = 100) String targetId,
        @NotBlank @Size(max = 1000) String reason
) {
}
