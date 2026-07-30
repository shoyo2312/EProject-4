package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.dto.response.ModerationActionResponse;
import com.tiktok.adminservice.dto.response.StatsSummaryResponse;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.service.AdminService;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class ModerationActionController {

    private final AdminService adminService;

    @GetMapping("/actions")
    public ApiResponse<Page<ModerationActionResponse>> listActions(
            @RequestParam(required = false) ReportTargetType targetType,
            @RequestParam(required = false) String targetId,
            Pageable pageable) {
        return ApiResponse.success(adminService.listActions(targetType, targetId, pageable));
    }

    @GetMapping("/stats/summary")
    public ApiResponse<StatsSummaryResponse> getStatsSummary() {
        return ApiResponse.success(adminService.getStatsSummary());
    }
}
