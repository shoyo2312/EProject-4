package com.tiktok.adminservice.dto.response;

import com.tiktok.moderation.Category;
import com.tiktok.moderation.rules.AdminTerm;

import java.time.Instant;

public record ModerationTermResponse(
        Long id,
        String term,
        /** Null for EXEMPT. */
        Category category,
        AdminTerm.Action action,
        String note,
        Long createdBy,
        Instant createdAt
) {
}
