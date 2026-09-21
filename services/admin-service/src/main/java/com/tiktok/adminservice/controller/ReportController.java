package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.dto.request.ResolveQueueRequest;
import com.tiktok.adminservice.dto.request.ResolveReportRequest;
import com.tiktok.adminservice.dto.request.SubmitReportRequest;
import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.dto.response.ReportGroupResponse;
import com.tiktok.adminservice.dto.response.ReportResponse;
import com.tiktok.adminservice.entity.ReportStatus;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.service.AdminService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/reports")
@RequiredArgsConstructor
public class ReportController {

    private final AdminService adminService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReportResponse> submit(
            @AuthenticationPrincipal Long currentUserId,
            @Valid @RequestBody SubmitReportRequest request) {
        return ApiResponse.success(adminService.submitReport(currentUserId, request));
    }

    @GetMapping
    public ApiResponse<Page<ReportResponse>> list(
            @RequestParam(required = false) ReportStatus status,
            @RequestParam(required = false) ReportTargetType targetType,
            Pageable pageable) {
        return ApiResponse.success(adminService.listReports(status, targetType, pageable));
    }

    /**
     * The worklist: one row per reported target, heaviest first. {@code /reports} stays the
     * report ledger — every row, filterable by status — because the two answer different
     * questions and collapsing them would leave no way to look a single report up.
     *
     * <p>Literal {@code /queue} is matched ahead of {@code /{reportId}}.
     */
    @GetMapping("/queue")
    public ApiResponse<Page<ReportGroupResponse>> queue(Pageable pageable) {
        return ApiResponse.success(adminService.listReportQueue(pageable));
    }

    /**
     * How many reports stand against one target. Its own endpoint rather than a field on the
     * listing because the console asks per expanded row, not per page. Literal {@code /count} is
     * matched ahead of {@code /{reportId}}.
     */
    @GetMapping("/count")
    public ApiResponse<Long> count(
            @RequestParam ReportTargetType targetType,
            @RequestParam String targetId) {
        return ApiResponse.success(adminService.countReports(targetType, targetId));
    }

    @GetMapping("/{reportId}")
    public ApiResponse<ReportResponse> getById(@PathVariable Long reportId) {
        return ApiResponse.success(adminService.getReport(reportId));
    }

    /**
     * Closes a whole queue row — the decision, plus every report standing against that target.
     * The per-report route below stays for the report ledger, where an admin opens one report by
     * id; this is the one the queue uses, and the literal path is matched ahead of
     * {@code /{reportId}/resolve}.
     */
    @PostMapping("/queue/resolve")
    public ApiResponse<ModerationActionResponse> resolveQueueRow(
            @AuthenticationPrincipal Long currentAdminId,
            @Valid @RequestBody ResolveQueueRequest request) {
        return ApiResponse.success(adminService.moderate(currentAdminId, request.targetType(),
                request.targetId(), request.actionType(), request.reason()));
    }

    @PostMapping("/{reportId}/resolve")
    public ApiResponse<ReportResponse> resolve(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable Long reportId,
            @Valid @RequestBody ResolveReportRequest request) {
        return ApiResponse.success(adminService.resolveReport(currentAdminId, reportId, request));
    }
}
