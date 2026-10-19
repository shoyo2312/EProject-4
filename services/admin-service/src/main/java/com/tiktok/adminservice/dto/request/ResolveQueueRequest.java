package com.tiktok.adminservice.dto.request;

import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.ReportTargetType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One decision about one reported target, closing every report standing against it.
 *
 * <p>The target rather than a report id, because that is what the queue row is: an admin looking
 * at a video forty people flagged is making one decision, and naming one of the forty reports to
 * carry it would put an arbitrary row in the audit log as though it were the reason.
 */
public record ResolveQueueRequest(
        @NotNull ReportTargetType targetType,
        @NotBlank @Size(max = 100) String targetId,
        @NotNull ModerationActionType actionType,
        @NotBlank @Size(max = 1000) String reason,

        /** How long a BAN_USER lasts, or null for one that does not lapse — see {@link ModerationRequest}. */
        @Min(1) @Max(365) Integer banDays
) {
}
