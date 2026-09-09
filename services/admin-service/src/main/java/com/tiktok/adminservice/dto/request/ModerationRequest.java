package com.tiktok.adminservice.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Shared by every action taken straight from the console — ban, takedown, and whatever follows.
 * The reason is the only field any of them carries, and it is the thing the audit row exists to
 * hold: an action nobody can explain later is worth little more than no record at all.
 */
public record ModerationRequest(
        @NotBlank @Size(max = 1000) String reason
) {
}
