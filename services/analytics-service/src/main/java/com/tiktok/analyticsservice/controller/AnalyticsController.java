package com.tiktok.analyticsservice.controller;

import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.DailyRevenueResponse;
import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;
import com.tiktok.analyticsservice.service.AnalyticsService;
import com.tiktok.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/engagement/daily")
    public ApiResponse<List<DailyCountResponse>> getDailyEngagement(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(analyticsService.getDailyEngagement(days));
    }

    @GetMapping("/engagement/videos/{videoId}")
    public ApiResponse<VideoEngagementSummaryResponse> getVideoEngagementSummary(@PathVariable String videoId) {
        return ApiResponse.success(analyticsService.getVideoEngagementSummary(videoId));
    }

    @GetMapping("/revenue/daily")
    public ApiResponse<List<DailyRevenueResponse>> getDailyRevenue(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(analyticsService.getDailyRevenue(days));
    }

    @GetMapping("/signups/daily")
    public ApiResponse<List<DailySignupResponse>> getDailySignups(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(analyticsService.getDailySignups(days));
    }
}
