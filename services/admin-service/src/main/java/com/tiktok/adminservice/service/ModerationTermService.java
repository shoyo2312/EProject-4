package com.tiktok.adminservice.service;

import com.tiktok.adminservice.dto.request.AddModerationTermRequest;
import com.tiktok.adminservice.dto.request.TextPreviewRequest;
import com.tiktok.adminservice.dto.response.ModerationTermResponse;
import com.tiktok.adminservice.dto.response.ModerationTermSnapshotResponse;
import com.tiktok.adminservice.dto.response.ModerationTermSummaryResponse;
import com.tiktok.adminservice.dto.response.TextPreviewResponse;
import com.tiktok.moderation.rules.AdminTerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * The admin-managed part of the text moderation list: terms added on top of the shipped file, or
 * taken out of it, without a deploy. See docs/moderation.md.
 */
public interface ModerationTermService {

    /** Live terms, newest first, optionally one action and/or containing {@code q}. */
    Page<ModerationTermResponse> list(String q, AdminTerm.Action action, Pageable pageable);

    ModerationTermSummaryResponse summary();

    ModerationTermResponse add(Long adminId, AddModerationTermRequest request);

    void delete(Long adminId, Long termId);

    /** What the services would make of a text with the list as it stands now. */
    TextPreviewResponse preview(TextPreviewRequest request);

    /** Every live term at one revision, for the services that poll it. */
    ModerationTermSnapshotResponse snapshot();
}
