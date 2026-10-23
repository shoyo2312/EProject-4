package com.tiktok.analyticsservice.controller;

import com.tiktok.analyticsservice.dto.request.CreatorVideoSort;
import com.tiktok.analyticsservice.dto.response.CreatorOverviewResponse;
import com.tiktok.analyticsservice.dto.response.CreatorVideoDetailResponse;
import com.tiktok.analyticsservice.dto.response.CreatorVideoStatsResponse;
import com.tiktok.analyticsservice.dto.response.CreatorViewersResponse;
import com.tiktok.analyticsservice.service.CreatorAnalyticsService;
import com.tiktok.common.response.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * TikTok Studio's analytics. Open to any signed-in account, unlike the rest of this service, and
 * always about the caller: there is no creator id to pass, so there is no one else's to ask for.
 * {@code days} is 7, 28, 60 or 365 in Studio's picker; anything up to a year is accepted.
 */
@RestController
@RequestMapping("/api/v1/analytics/creator")
@RequiredArgsConstructor
@Validated
public class CreatorAnalyticsController {

    private final CreatorAnalyticsService creatorAnalyticsService;

    @GetMapping("/overview")
    public ApiResponse<CreatorOverviewResponse> overview(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days) {
        return ApiResponse.success(creatorAnalyticsService.overview(currentUserId, days));
    }

    /** Literal path, so it is matched ahead of {@code /videos/{videoId}}. */
    @GetMapping("/videos/top")
    public ApiResponse<List<CreatorVideoStatsResponse>> topVideos(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days,
            @RequestParam(defaultValue = "VIEWS") CreatorVideoSort sort,
            @RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        return ApiResponse.success(creatorAnalyticsService.topVideos(currentUserId, days, sort, limit));
    }

    @GetMapping("/videos/{videoId}")
    public ApiResponse<CreatorVideoDetailResponse> video(
            @AuthenticationPrincipal Long currentUserId,
            @PathVariable String videoId,
            @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days) {
        return ApiResponse.success(creatorAnalyticsService.video(currentUserId, videoId, days));
    }

    @GetMapping("/viewers")
    public ApiResponse<CreatorViewersResponse> viewers(
            @AuthenticationPrincipal Long currentUserId,
            @RequestParam(defaultValue = "7") @Min(1) @Max(365) int days) {
        return ApiResponse.success(creatorAnalyticsService.viewers(currentUserId, days));
    }
}
