package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.response.DailyActiveUsersResponse;
import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import com.tiktok.analyticsservice.dto.response.TopVideoResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.analyticsservice.repository.UserSignupEventRepository;
import com.tiktok.analyticsservice.repository.WatchEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalyticsServiceImpl implements AnalyticsService {

    private final EngagementEventRepository engagementEventRepository;
    private final UserSignupEventRepository userSignupEventRepository;
    private final WatchEventRepository watchEventRepository;

    @Override
    public List<DailyCountResponse> getDailyEngagement(int days) {
        return engagementEventRepository.findDailyCounts(days);
    }

    @Override
    public VideoEngagementSummaryResponse getVideoEngagementSummary(String videoId) {
        return engagementEventRepository.findSummaryByVideoId(videoId);
    }

    @Override
    public List<DailySignupResponse> getDailySignups(int days) {
        return userSignupEventRepository.findDailySignups(days);
    }

    @Override
    public List<DailyActiveUsersResponse> getDailyActiveUsers(int days) {
        return watchEventRepository.findDailyActiveUsers(days);
    }

    @Override
    public List<TopVideoResponse> getTopVideos(int days, int limit, Instant until) {
        Instant end = until != null ? until : Instant.now();
        return watchEventRepository.findTopVideos(end.minus(days, ChronoUnit.DAYS), end, limit);
    }
}
