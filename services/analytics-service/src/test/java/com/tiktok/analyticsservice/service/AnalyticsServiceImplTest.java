package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.DailyRevenueResponse;
import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.analyticsservice.repository.RevenueEventRepository;
import com.tiktok.analyticsservice.repository.UserSignupEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceImplTest {

    @Mock
    private EngagementEventRepository engagementEventRepository;

    @Mock
    private RevenueEventRepository revenueEventRepository;

    @Mock
    private UserSignupEventRepository userSignupEventRepository;

    @Test
    void getDailyEngagement_delegatesToRepository() {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(engagementEventRepository, revenueEventRepository, userSignupEventRepository);
        List<DailyCountResponse> expected = List.of(new DailyCountResponse(LocalDate.of(2026, 7, 24), "LIKED", 5));
        when(engagementEventRepository.findDailyCounts(7)).thenReturn(expected);

        List<DailyCountResponse> result = service.getDailyEngagement(7);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getVideoEngagementSummary_delegatesToRepository() {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(engagementEventRepository, revenueEventRepository, userSignupEventRepository);
        VideoEngagementSummaryResponse expected = new VideoEngagementSummaryResponse("v1", 10, 2, 1);
        when(engagementEventRepository.findSummaryByVideoId("v1")).thenReturn(expected);

        VideoEngagementSummaryResponse result = service.getVideoEngagementSummary("v1");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getDailyRevenue_delegatesToRepository() {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(engagementEventRepository, revenueEventRepository, userSignupEventRepository);
        List<DailyRevenueResponse> expected = List.of(new DailyRevenueResponse(LocalDate.of(2026, 7, 24), 3, 2, BigDecimal.TEN));
        when(revenueEventRepository.findDailyRevenue(30)).thenReturn(expected);

        List<DailyRevenueResponse> result = service.getDailyRevenue(30);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getDailySignups_delegatesToRepository() {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(engagementEventRepository, revenueEventRepository, userSignupEventRepository);
        List<DailySignupResponse> expected = List.of(new DailySignupResponse(LocalDate.of(2026, 7, 24), 4));
        when(userSignupEventRepository.findDailySignups(1)).thenReturn(expected);

        List<DailySignupResponse> result = service.getDailySignups(1);

        assertThat(result).isEqualTo(expected);
    }
}
