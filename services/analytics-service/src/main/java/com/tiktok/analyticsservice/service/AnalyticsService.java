package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.DailyRevenueResponse;
import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;

import java.util.List;

public interface AnalyticsService {

    List<DailyCountResponse> getDailyEngagement(int days);

    VideoEngagementSummaryResponse getVideoEngagementSummary(String videoId);

    List<DailyRevenueResponse> getDailyRevenue(int days);

    List<DailySignupResponse> getDailySignups(int days);
}
