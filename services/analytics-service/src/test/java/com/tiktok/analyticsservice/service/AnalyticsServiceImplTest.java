package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.response.DailyCountResponse;
import com.tiktok.analyticsservice.dto.response.DailySignupResponse;
import com.tiktok.analyticsservice.dto.response.VideoEngagementSummaryResponse;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.analyticsservice.repository.UserSignupEventRepository;
import com.tiktok.analyticsservice.repository.WatchEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceImplTest {

    @Mock
    private EngagementEventRepository engagementEventRepository;

    @Mock
    private UserSignupEventRepository userSignupEventRepository;

    @Mock
    private WatchEventRepository watchEventRepository;

    @Test
    void getDailyEngagement_delegatesToRepository() {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(engagementEventRepository, userSignupEventRepository, watchEventRepository);
        List<DailyCountResponse> expected = List.of(new DailyCountResponse(LocalDate.of(2026, 7, 24), "LIKED", 5));
        when(engagementEventRepository.findDailyCounts(7)).thenReturn(expected);

        List<DailyCountResponse> result = service.getDailyEngagement(7);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getVideoEngagementSummary_delegatesToRepository() {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(engagementEventRepository, userSignupEventRepository, watchEventRepository);
        VideoEngagementSummaryResponse expected = new VideoEngagementSummaryResponse("v1", 10, 2, 1);
        when(engagementEventRepository.findSummaryByVideoId("v1")).thenReturn(expected);

        VideoEngagementSummaryResponse result = service.getVideoEngagementSummary("v1");

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void getDailySignups_delegatesToRepository() {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(engagementEventRepository, userSignupEventRepository, watchEventRepository);
        List<DailySignupResponse> expected = List.of(new DailySignupResponse(LocalDate.of(2026, 7, 24), 4));
        when(userSignupEventRepository.findDailySignups(1)).thenReturn(expected);

        List<DailySignupResponse> result = service.getDailySignups(1);

        assertThat(result).isEqualTo(expected);
    }

    /** The console's "as of" day ends the window, and the window is the asked-for days long. */
    @Test
    void topVideosEndWhereTheConsoleAsks() {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(engagementEventRepository, userSignupEventRepository, watchEventRepository);
        Instant until = Instant.parse("2026-10-01T00:00:00Z");

        service.getTopVideos(7, 20, until);

        verify(watchEventRepository).findTopVideos(Instant.parse("2026-09-24T00:00:00Z"), until, 20);
    }

    @Test
    void topVideosWithoutAnEndAreTheDaysUpToNow() {
        AnalyticsServiceImpl service = new AnalyticsServiceImpl(engagementEventRepository, userSignupEventRepository, watchEventRepository);
        Instant before = Instant.now();

        service.getTopVideos(30, 10, null);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
        verify(watchEventRepository).findTopVideos(from.capture(), to.capture(), eq(10));
        assertThat(to.getValue()).isBetween(before, Instant.now());
        assertThat(Duration.between(from.getValue(), to.getValue())).isEqualTo(Duration.ofDays(30));
    }
}
