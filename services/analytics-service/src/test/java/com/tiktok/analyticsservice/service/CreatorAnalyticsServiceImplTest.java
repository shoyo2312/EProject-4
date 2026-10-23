package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.request.CreatorVideoSort;
import com.tiktok.analyticsservice.dto.response.CreatorOverviewResponse;
import com.tiktok.analyticsservice.dto.response.CreatorViewersResponse;
import com.tiktok.analyticsservice.exception.CreatorVideoNotFoundException;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.DailyEngagement;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.DailyFollows;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.DailyViews;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.EngagementTotals;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.WatchTotals;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreatorAnalyticsServiceImplTest {

    private final CreatorAnalyticsRepository repository = mock(CreatorAnalyticsRepository.class);
    private final CreatorAnalyticsServiceImpl service = new CreatorAnalyticsServiceImpl(repository, 3);

    /** Vietnam's midnight is 17:00 UTC the day before; a UTC midnight would cut the evening off. */
    @Test
    void aWindowIsWholeVietnamDaysEndingTomorrowsMidnight() {
        var window = CreatorAnalyticsServiceImpl.Window.endingOn(LocalDate.of(2026, 10, 23), 7);

        assertThat(window.firstDay()).isEqualTo(LocalDate.of(2026, 10, 17));
        assertThat(window.from()).isEqualTo(Instant.parse("2026-10-16T17:00:00Z"));
        assertThat(window.to()).isEqualTo(Instant.parse("2026-10-23T17:00:00Z"));
        assertThat(window.previousFrom()).isEqualTo(Instant.parse("2026-10-09T17:00:00Z"));
    }

    @Test
    void theChartHasEveryDayEvenTheQuietOnes() {
        LocalDate first = LocalDate.of(2026, 10, 1);

        var days = CreatorAnalyticsServiceImpl.daily(first, first.plusDays(2),
                List.of(new DailyViews(first.plusDays(1), 5)),
                List.of(new DailyEngagement(first, 1, 2, 3)),
                List.of(new DailyFollows(first.plusDays(2), 4, 1)));

        assertThat(days).hasSize(3);
        assertThat(days.get(0).likes()).isEqualTo(1);
        assertThat(days.get(1).views()).isEqualTo(5);
        assertThat(days.get(1).likes()).isZero();
        assertThat(days.get(2).followersGained()).isEqualTo(4);
    }

    @Test
    void theOverviewComparesWithTheWindowJustBefore() {
        LocalDate today = LocalDate.now(CreatorAnalyticsServiceImpl.ZONE);
        when(repository.dailyViews(any(), any(), any())).thenReturn(List.of(
                new DailyViews(today, 10),
                new DailyViews(today.minusDays(2), 4)));
        when(repository.dailyFollows(anyLong(), any(), any())).thenReturn(List.of(
                new DailyFollows(today, 3, 1)));

        CreatorOverviewResponse overview = service.overview(7L, 2);

        assertThat(overview.daily()).extracting("day").containsExactly(today.minusDays(1), today);
        assertThat(overview.current().views()).isEqualTo(10);
        assertThat(overview.current().netFollowers()).isEqualTo(2);
        assertThat(overview.previous().views()).isEqualTo(4);
    }

    /** Ten views stopping at 0%, 50% and the end: everyone starts, 9 of 10 reach halfway. */
    @Test
    void retentionCountsWhoWasStillWatching() {
        long[] buckets = new long[21];
        buckets[0] = 1;
        buckets[10] = 4;
        buckets[20] = 5;

        List<Double> retention = CreatorAnalyticsServiceImpl.retention(buckets);

        assertThat(retention).hasSize(21);
        assertThat(retention.get(0)).isEqualTo(1.0);
        assertThat(retention.get(1)).isEqualTo(0.9);
        assertThat(retention.get(10)).isEqualTo(0.9);
        assertThat(retention.get(11)).isEqualTo(0.5);
        assertThat(retention.get(20)).isEqualTo(0.5);
    }

    @Test
    void retentionOfAnUnwatchedVideoIsFlatZero() {
        assertThat(CreatorAnalyticsServiceImpl.retention(new long[21])).containsOnly(0.0);
    }

    @Test
    void someoneElsesVideoIsNotFound() {
        when(repository.ownsVideo(7L, "v1")).thenReturn(false);

        assertThatThrownBy(() -> service.video(7L, "v1", 7))
                .isInstanceOf(CreatorVideoNotFoundException.class);
        verify(repository, never()).retentionBuckets(any());
    }

    @Test
    void topVideosJoinWatchesAndEngagementAndRankByTheMetricAsked() {
        when(repository.watchTotals(any(), any(), any())).thenReturn(List.of(
                new WatchTotals("a", 100, 50, 0, 0),
                new WatchTotals("b", 10, 5, 0, 0)));
        when(repository.engagementTotals(any(), any(), any())).thenReturn(List.of(
                new EngagementTotals("b", 30, 0, 0),
                new EngagementTotals("c", 1, 0, 0)));

        assertThat(service.topVideos(7L, 7, CreatorVideoSort.VIEWS, 10))
                .extracting("videoId").containsExactly("a", "b", "c");
        assertThat(service.topVideos(7L, 7, CreatorVideoSort.LIKES, 2))
                .extracting("videoId").containsExactly("b", "c");
    }

    @Test
    void aSmallAudienceGetsTotalsButNoBreakdown() {
        when(repository.viewers(anyLong(), any(), any())).thenReturn(2L);

        CreatorViewersResponse viewers = service.viewers(7L, 7);

        assertThat(viewers.totalViewers()).isEqualTo(2);
        assertThat(viewers.breakdownAvailable()).isFalse();
        assertThat(viewers.activeHours()).isNull();
        verify(repository, never()).viewersByHour(anyLong(), any(), any());
    }

    @Test
    void anAudienceAtTheThresholdGetsTheBreakdown() {
        when(repository.viewers(anyLong(), any(), any())).thenReturn(3L);
        when(repository.viewersByHour(anyLong(), any(), any())).thenReturn(new long[24]);
        when(repository.viewersByWeekday(anyLong(), any(), any())).thenReturn(new long[7]);

        CreatorViewersResponse viewers = service.viewers(7L, 7);

        assertThat(viewers.breakdownAvailable()).isTrue();
        assertThat(viewers.activeHours()).hasSize(24);
        assertThat(viewers.activeDays()).hasSize(7);
    }
}
