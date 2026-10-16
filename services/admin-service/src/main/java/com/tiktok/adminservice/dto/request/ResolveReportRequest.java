package com.tiktok.adminservice.dto.request;

import com.tiktok.adminservice.entity.ModerationActionType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResolveReportRequest(
        @NotNull ModerationActionType actionType,
        @NotBlank @Size(max = 1000) String reason,

        /** How long a BAN_USER lasts, or null for one that does not lapse — see {@link ModerationRequest}. */
        @Min(1) @Max(365) Integer banDays
) {
}
