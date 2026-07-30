package com.tiktok.adminservice.dto.request;

import com.tiktok.adminservice.entity.ModerationActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveReportRequest(
        @NotNull ModerationActionType actionType,
        @NotBlank @Size(max = 1000) String reason
) {
}
