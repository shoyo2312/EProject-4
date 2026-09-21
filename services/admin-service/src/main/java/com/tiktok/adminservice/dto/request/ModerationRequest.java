package com.tiktok.adminservice.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shared by every action taken straight from the console — ban, takedown, and whatever follows.
 * The reason is the only field any of them carries, and it is the thing the audit row exists to
 * hold: an action nobody can explain later is worth little more than no record at all.
 */
public record ModerationRequest(
        @NotBlank @Size(max = 1000) String reason,

        /**
         * How long a ban lasts, or null for one that does not lapse. Ignored by every action but
         * BAN_USER — a takedown has no duration, and a temporary one would have to be reversed
         * by something, which nothing here does.
         */
        @Min(1) @Max(365) Integer banDays
) {
}
