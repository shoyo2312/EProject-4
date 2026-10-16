package com.tiktok.adminservice.dto.response;

import com.tiktok.moderation.Category;
import com.tiktok.moderation.rules.AdminTerm;

import java.util.List;

/**
 * The whole admin list at one revision, for the services' {@code AdminTermsSync}. A snapshot, not a
 * change: a service replaces what it held with this.
 */
public record ModerationTermSnapshotResponse(long revision, List<Term> terms) {

    public record Term(String term, Category category, AdminTerm.Action action) {
    }
}
