package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.response.DailyActiveUsersResponse;
import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import com.tiktok.analyticsservice.dto.response.TopVideoResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;

import java.time.Instant;
import java.util.List;

public interface AnalyticsService {

    List<DailyCountResponse> getDailyEngagement(int days);

    VideoEngagementSummaryResponse getVideoEngagementSummary(String videoId);

    List<DailySignupResponse> getDailySignups(int days);

    List<DailyActiveUsersResponse> getDailyActiveUsers(int days);

    /** The {@code days} before {@code until}, end exclusive; before now when {@code until} is null. */
    List<TopVideoResponse> getTopVideos(int days, int limit, Instant until);
}
