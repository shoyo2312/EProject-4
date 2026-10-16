package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.dto.request.AddModerationTermRequest;
import com.tiktok.adminservice.dto.request.TextPreviewRequest;
import com.tiktok.adminservice.dto.response.ModerationTermResponse;
import com.tiktok.adminservice.dto.response.ModerationTermSummaryResponse;
import com.tiktok.adminservice.dto.response.TextPreviewResponse;
import com.tiktok.adminservice.service.ModerationTermService;
import com.tiktok.common.response.ApiResponse;
import com.tiktok.moderation.rules.AdminTerm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The console's word list: terms added on top of the list every service ships with, or taken out
 * of it, live within a minute and without a deploy — and a preview to check one before relying on
 * it. See docs/moderation.md.
 */
@RestController
@RequestMapping("/api/v1/admin/moderation")
@RequiredArgsConstructor
public class ModerationTermController {

    private final ModerationTermService moderationTermService;

    /** Newest first; {@code q} matches anywhere in the term, case-insensitively. */
    @GetMapping("/terms")
    public ApiResponse<Page<ModerationTermResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) AdminTerm.Action action,
            Pageable pageable) {
        return ApiResponse.success(moderationTermService.list(q, action, pageable));
    }

    @GetMapping("/terms/summary")
    public ApiResponse<ModerationTermSummaryResponse> summary() {
        return ApiResponse.success(moderationTermService.summary());
    }

    @PostMapping("/terms")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ModerationTermResponse> add(
            @AuthenticationPrincipal Long currentAdminId,
            @Valid @RequestBody AddModerationTermRequest request) {
        return ApiResponse.success(moderationTermService.add(currentAdminId, request));
    }

    @DeleteMapping("/terms/{termId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable Long termId) {
        moderationTermService.delete(currentAdminId, termId);
        return ApiResponse.success(null);
    }

    @PostMapping("/text-preview")
    public ApiResponse<TextPreviewResponse> preview(@Valid @RequestBody TextPreviewRequest request) {
        return ApiResponse.success(moderationTermService.preview(request));
    }
}
