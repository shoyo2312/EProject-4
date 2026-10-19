package com.tiktok.interactionservice.dto.response;

import java.time.Instant;

/**
 * Where an account stands with the comment strikes, for the moderation console.
 *
 * @param restrictedUntil when the current restriction ends; null when there is none
 * @param strikes         strikes counted toward the next restriction (reset when one starts)
 * @param step            restrictions within the step window — the next one lasts the duration after
 *                        this many
 */
public record CommentRestrictionResponse(
        Long userId,
        Instant restrictedUntil,
        long strikes,
        long step
) {
}
