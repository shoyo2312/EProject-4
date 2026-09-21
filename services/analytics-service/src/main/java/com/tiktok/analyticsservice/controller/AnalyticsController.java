package com.tiktok.analyticsservice.controller;

import com.tiktok.analyticsservice.dto.response.DailyActiveUsersResponse;
import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import com.tiktok.analyticsservice.dto.response.TopVideoResponse;
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

    /** Distinct viewers per day — the one number that says whether anyone is still here. */
    @GetMapping("/active-users/daily")
    public ApiResponse<List<DailyActiveUsersResponse>> getDailyActiveUsers(
            @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days) {
        return ApiResponse.success(analyticsService.getDailyActiveUsers(days));
    }

    /**
     * The most-watched videos of the window. The limit is capped for the same reason the window
     * is: this aggregates every watch row in the slice, and nothing on a screen needs a thousand.
     */
    @GetMapping("/videos/top")
    public ApiResponse<List<TopVideoResponse>> getTopVideos(
            @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return ApiResponse.success(analyticsService.getTopVideos(days, limit));
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
