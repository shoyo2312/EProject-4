package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.dto.request.ResolveReportRequest;
import com.tiktok.adminservice.dto.request.SubmitReportRequest;
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

    @GetMapping("/{reportId}")
    public ApiResponse<ReportResponse> getById(@PathVariable Long reportId) {
        return ApiResponse.success(adminService.getReport(reportId));
    }

    @PostMapping("/{reportId}/resolve")
    public ApiResponse<ReportResponse> resolve(
            @AuthenticationPrincipal Long currentAdminId,
            @PathVariable Long reportId,
            @Valid @RequestBody ResolveReportRequest request) {
        return ApiResponse.success(adminService.resolveReport(currentAdminId, reportId, request));
    }
}
