package com.tiktok.adminservice.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One DLT record, by the coordinates the listing gave it.
 *
 * @param force replay even though the original partition has a later record under the same key —
 *              only after reading what that record is
 */
public record ReplayDeadLetterRequest(
        @NotNull @PositiveOrZero Integer partition,
        @NotNull @PositiveOrZero Long offset,
        boolean force
) {
}
