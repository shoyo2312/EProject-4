package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.request.CreatorVideoSort;
import com.tiktok.analyticsservice.dto.response.CreatorOverviewResponse;
import com.tiktok.analyticsservice.dto.response.CreatorVideoDetailResponse;
import com.tiktok.analyticsservice.dto.response.CreatorViewersResponse;
import com.tiktok.analyticsservice.dto.response.SearchQueryResponse;
import com.tiktok.analyticsservice.dto.response.TrafficSourceResponse;
import com.tiktok.analyticsservice.exception.CreatorVideoNotFoundException;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.DailyEngagement;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.DailyFollows;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.DailyViews;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.EngagementTotals;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.NewViewers;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.WatchTotals;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class CreatorAnalyticsServiceImplTest {

    private final CreatorAnalyticsRepository repository = mock(CreatorAnalyticsRepository.class);
    private final CreatorAnalyticsServiceImpl service = new CreatorAnalyticsServiceImpl(repository, 3, 3);

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
                List.of(new DailyEngagement(first, 1, 2, 3, 0)),
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
                new EngagementTotals("b", 30, 0, 0, 0),
                new EngagementTotals("c", 1, 0, 0, 0)));

        assertThat(service.topVideos(7L, 7, CreatorVideoSort.VIEWS, 10))
                .extracting("videoId").containsExactly("a", "b", "c");
        assertThat(service.topVideos(7L, 7, CreatorVideoSort.LIKES, 2))
                .extracting("videoId").containsExactly("b", "c");
    }

    @Test
    void topVideosCarryTheNewViewersEachVideoBroughtIn() {
        when(repository.watchTotals(any(), any(), any())).thenReturn(List.of(
                new WatchTotals("a", 100, 50, 0, 0),
                new WatchTotals("b", 10, 5, 0, 0)));
        when(repository.newViewersByVideo(anyLong(), any(), any())).thenReturn(List.of(
                new NewViewers("b", 4)));

        assertThat(service.topVideos(7L, 7, CreatorVideoSort.NEW_VIEWERS, 10))
                .extracting("videoId", "newViewers")
                .containsExactly(tuple("b", 4L), tuple("a", 0L));
    }

    /**
     * The memo key carries parameters the caller picks, so a signed-in user looping over them
     * could grow it without end. It is capped: past the cap the oldest answer goes.
     */
    @Test
    void theMemo_dropsTheOldestAnswerPastItsCap() {
        when(repository.viewers(anyLong(), any(), any())).thenReturn(2L);

        // One load reads two windows, so two calls per load.
        service.viewers(1L, 7);
        service.viewers(1L, 7);
        verify(repository, times(2)).viewers(eq(1L), any(), any());

        for (long creator = 2; creator <= CreatorAnalyticsServiceImpl.MEMO_MAX_ENTRIES + 1; creator++) {
            service.viewers(creator, 7);
        }
        service.viewers(1L, 7);

        verify(repository, times(4)).viewers(eq(1L), any(), any());
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

    /** Studio asks these on every tab switch, and each one is several FINAL scans: a minute's answer is reused. */
    @Test
    void theSameQuestionWithinAMinute_readsClickHouseOnce() {
        service.overview(7L, 7);
        service.overview(7L, 7);
        service.overview(7L, 28);

        verify(repository, times(2)).dailyViews(any(), any(), any());
    }

    /** Lifetime totals at the top; {@code current} and {@code previous} are the window and the one before it. */
    @Test
    void theVideoPageTotalsBothWindows() {
        var window = CreatorAnalyticsServiceImpl.Window.endingOn(LocalDate.now(CreatorAnalyticsServiceImpl.ZONE), 7);
        when(repository.ownsVideo(7L, "v1")).thenReturn(true);
        when(repository.watchTotals(any(), eq(Instant.EPOCH), eq(window.to())))
                .thenReturn(List.of(new WatchTotals("v1", 100, 50, 0, 0)));
        when(repository.watchTotals(any(), eq(window.from()), eq(window.to())))
                .thenReturn(List.of(new WatchTotals("v1", 10, 5, 0, 0)));
        when(repository.watchTotals(any(), eq(window.previousFrom()), eq(window.from())))
                .thenReturn(List.of(new WatchTotals("v1", 4, 2, 0, 0)));
        when(repository.followsFromVideo("v1", Instant.EPOCH, window.to())).thenReturn(9L);
        when(repository.followsFromVideo("v1", window.from(), window.to())).thenReturn(3L);
        when(repository.followsFromVideo("v1", window.previousFrom(), window.from())).thenReturn(1L);
        when(repository.retentionBuckets("v1")).thenReturn(new long[21]);

        CreatorVideoDetailResponse video = service.video(7L, "v1", 7);

        assertThat(video.views()).isEqualTo(100);
        assertThat(video.newFollowers()).isEqualTo(9);
        assertThat(video.current().views()).isEqualTo(10);
        assertThat(video.current().newFollowers()).isEqualTo(3);
        assertThat(video.previous().views()).isEqualTo(4);
        assertThat(video.previous().newFollowers()).isEqualTo(1);
        assertThat(video.daily()).hasSize(7);
        verify(repository).dailyFollowsFromVideo("v1", window.from(), window.to());
    }

    @Test
    void savesRideEveryTotal() {
        LocalDate today = LocalDate.now(CreatorAnalyticsServiceImpl.ZONE);
        when(repository.ownsVideo(7L, "v1")).thenReturn(true);
        when(repository.retentionBuckets("v1")).thenReturn(new long[21]);
        when(repository.engagementTotals(any(), any(), any()))
                .thenReturn(List.of(new EngagementTotals("v1", 1, 2, 3, 5)));
        when(repository.dailyEngagement(any(), any(), any()))
                .thenReturn(List.of(new DailyEngagement(today, 1, 2, 3, 4)));

        CreatorVideoDetailResponse video = service.video(7L, "v1", 7);
        assertThat(video.saves()).isEqualTo(5);
        assertThat(video.current().saves()).isEqualTo(5);
        assertThat(video.daily().getLast().saves()).isEqualTo(4);
        assertThat(service.overview(7L, 7).current().saves()).isEqualTo(4);
        assertThat(service.topVideos(7L, 7, CreatorVideoSort.VIEWS, 10)).singleElement()
                .satisfies(stats -> assertThat(stats.saves()).isEqualTo(5));
    }

    /** Traffic and queries are the window's, the way TikTok shows them, not lifetime like the totals. */
    @Test
    void trafficAndQueriesAreTheWindows() {
        var window = CreatorAnalyticsServiceImpl.Window.endingOn(LocalDate.now(CreatorAnalyticsServiceImpl.ZONE), 7);
        when(repository.ownsVideo(7L, "v1")).thenReturn(true);
        when(repository.retentionBuckets("v1")).thenReturn(new long[21]);
        when(repository.trafficSources("v1", window.from(), window.to()))
                .thenReturn(List.of(new TrafficSourceResponse("FOR_YOU", 3)));
        when(repository.searchQueries("v1", window.from(), window.to(), 3, 10))
                .thenReturn(List.of(new SearchQueryResponse("cat", 3)));

        CreatorVideoDetailResponse video = service.video(7L, "v1", 7);

        assertThat(video.trafficSources()).containsExactly(new TrafficSourceResponse("FOR_YOU", 3));
        assertThat(video.searchQueries()).containsExactly(new SearchQueryResponse("cat", 3));
        verify(repository, never()).trafficSources(any(), eq(Instant.EPOCH), any());
    }
}
