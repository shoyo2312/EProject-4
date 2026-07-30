package com.tiktok.adminservice.dto.request;

import com.tiktok.adminservice.entity.ReportTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SubmitReportRequest(
        @NotNull ReportTargetType targetType,
        @NotBlank String targetId,
        @NotBlank @Size(max = 1000) String reason
) {
}
