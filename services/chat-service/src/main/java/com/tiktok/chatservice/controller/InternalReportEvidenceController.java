package com.tiktok.chatservice.controller;

import com.tiktok.chatservice.dto.request.ReportEvidenceRequest;
import com.tiktok.chatservice.dto.response.ReportEvidenceResponse;
import com.tiktok.chatservice.service.ReportEvidenceService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service-to-service, for admin-service when a user files a report. Under the same internal prefix
 * as the data export, so api-gateway denies it and {@code SecurityConfig} checks the internal token.
 * A POST because the picked ids do not fit a URL.
 */
@RestController
@RequestMapping("/api/v1/conversations/internal")
@RequiredArgsConstructor
public class InternalReportEvidenceController {

    private final ReportEvidenceService reportEvidenceService;

    @PostMapping("/report-evidence")
    public ApiResponse<ReportEvidenceResponse> capture(@Valid @RequestBody ReportEvidenceRequest request) {
        return ApiResponse.success(reportEvidenceService.capture(request));
    }
}
