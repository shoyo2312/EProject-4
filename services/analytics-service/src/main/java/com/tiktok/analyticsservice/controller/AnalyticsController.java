package com.tiktok.analyticsservice.controller;

import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;
import com.tiktok.analyticsservice.service.AnalyticsService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
@Validated
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/engagement/daily")
    public ApiResponse<List<DailyCountResponse>> getDailyEngagement(
            @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days) {
        return ApiResponse.success(analyticsService.getDailyEngagement(days));
    }

    @GetMapping("/engagement/videos/{videoId}")
    public ApiResponse<VideoEngagementSummaryResponse> getVideoEngagementSummary(@PathVariable String videoId) {
        return ApiResponse.success(analyticsService.getVideoEngagementSummary(videoId));
    }

    @GetMapping("/signups/daily")
    /**
     * The window is bounded at both ends: a negative one asks ClickHouse for a slice in the
     * future and comes back empty, which reads as lost data, and an unbounded one is a full scan
     * with FINAL over every row the platform has ever written.
     */
    public ApiResponse<List<DailySignupResponse>> getDailySignups(
            @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days) {
        return ApiResponse.success(analyticsService.getDailySignups(days));
    }
}
