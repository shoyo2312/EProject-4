package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.DailyRevenueResponse;
import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.analyticsservice.repository.RevenueEventRepository;
import com.tiktok.analyticsservice.repository.UserSignupEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private final EngagementEventRepository engagementEventRepository;
    private final RevenueEventRepository revenueEventRepository;
    private final UserSignupEventRepository userSignupEventRepository;

    @Override
    public List<DailyCountResponse> getDailyEngagement(int days) {
        return engagementEventRepository.findDailyCounts(days);
    }

    @Override
    public VideoEngagementSummaryResponse getVideoEngagementSummary(String videoId) {
        return engagementEventRepository.findSummaryByVideoId(videoId);
    }

    @Override
    public List<DailyRevenueResponse> getDailyRevenue(int days) {
        return revenueEventRepository.findDailyRevenue(days);
    }

    @Override
    public List<DailySignupResponse> getDailySignups(int days) {
        return userSignupEventRepository.findDailySignups(days);
    }
}
