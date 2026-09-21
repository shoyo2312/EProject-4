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

    /**
     * How many times this target has been enforced against. The console reads it before a ban so
     * whoever is deciding can see it is the account's fourth offence and not its first.
     */
    @GetMapping("/actions/strikes")
    public ApiResponse<Long> countStrikes(
            @RequestParam ReportTargetType targetType,
            @RequestParam String targetId) {
        return ApiResponse.success(adminService.countStrikes(targetType, targetId));
    }

    @GetMapping("/stats/summary")
    public ApiResponse<StatsSummaryResponse> getStatsSummary() {
        return ApiResponse.success(adminService.getStatsSummary());
    }
}
