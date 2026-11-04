package com.tiktok.analyticsservice.service;

import com.tiktok.analyticsservice.dto.request.CreatorVideoSort;
import com.tiktok.analyticsservice.dto.response.CreatorDailyStatsResponse;
import com.tiktok.analyticsservice.dto.response.CreatorOverviewResponse;
import com.tiktok.analyticsservice.dto.response.CreatorTotalsResponse;
import com.tiktok.analyticsservice.dto.response.CreatorVideoDetailResponse;
import com.tiktok.analyticsservice.dto.response.CreatorVideoStatsResponse;
import com.tiktok.analyticsservice.dto.response.CreatorViewersResponse;
import com.tiktok.analyticsservice.exception.CreatorVideoNotFoundException;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.DailyEngagement;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.DailyFollows;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.DailyViews;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.EngagementTotals;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.NewViewers;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.Scope;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.WatchTotals;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
public class CreatorAnalyticsServiceImpl implements CreatorAnalyticsService {

    static final ZoneId ZONE = ZoneId.of(CreatorAnalyticsRepository.ZONE);

    private static final WatchTotals NO_WATCHES = new WatchTotals(null, 0, 0, 0, 0);
    private static final EngagementTotals NO_ENGAGEMENT = new EngagementTotals(null, 0, 0, 0);

    private final CreatorAnalyticsRepository repository;

    /**
     * Viewers a creator needs before Studio breaks them down by hour and weekday. TikTok holds the
     * same breakdowns back until there is enough data; it is a property only so a demo with a few
     * test accounts can lower it.
     */
    private final long minAudience;

    public CreatorAnalyticsServiceImpl(CreatorAnalyticsRepository repository,
                                       @Value("${analytics.creator.min-audience:100}") long minAudience) {
        this.repository = repository;
        this.minAudience = minAudience;
    }

    /**
     * The last {@code days} calendar days ending today, as instants, plus the window of the same
     * length just before it. End exclusive: {@code to} is tomorrow's midnight.
     */
    record Window(LocalDate firstDay, LocalDate lastDay, Instant from, Instant to, Instant previousFrom) {

        static Window endingOn(LocalDate today, int days) {
            LocalDate first = today.minusDays(days - 1L);
            return new Window(first, today, startOf(first), startOf(today.plusDays(1)),
                    startOf(first.minusDays(days)));
        }

        private static Instant startOf(LocalDate day) {
            return day.atStartOfDay(ZONE).toInstant();
        }
    }

    private static Window window(int days) {
        return Window.endingOn(LocalDate.now(ZONE), days);
    }

    @Override
    public CreatorOverviewResponse overview(long creatorId, int days) {
        Window window = window(days);
        Scope scope = Scope.ownedBy(creatorId);

        // Both windows in one read per stream, split by day afterwards.
        List<CreatorDailyStatsResponse> both = daily(window.firstDay().minusDays(days), window.lastDay(),
                repository.dailyViews(scope, window.previousFrom(), window.to()),
                repository.dailyEngagement(scope, window.previousFrom(), window.to()),
                repository.dailyFollows(creatorId, window.previousFrom(), window.to()));

        List<CreatorDailyStatsResponse> current = both.subList(days, both.size());
        return new CreatorOverviewResponse(totals(current), totals(both.subList(0, days)), List.copyOf(current));
    }

    @Override
    public List<CreatorVideoStatsResponse> topVideos(long creatorId, int days, CreatorVideoSort sort, int limit) {
        Window window = window(days);
        Scope scope = Scope.ownedBy(creatorId);
        Map<String, WatchTotals> watches = byVideo(repository.watchTotals(scope, window.from(), window.to()),
                WatchTotals::videoId);
        Map<String, EngagementTotals> engagement = byVideo(
                repository.engagementTotals(scope, window.from(), window.to()), EngagementTotals::videoId);
        // A new viewer's first watch is in the window, so their video is already among the watches.
        Map<String, Long> newViewers = repository.newViewersByVideo(creatorId, window.from(), window.to()).stream()
                .collect(Collectors.toMap(NewViewers::videoId, NewViewers::newViewers));

        Set<String> videoIds = new HashSet<>(watches.keySet());
        videoIds.addAll(engagement.keySet());
        return videoIds.stream()
                .map(id -> stats(id, watches.getOrDefault(id, NO_WATCHES), newViewers.getOrDefault(id, 0L),
                        engagement.getOrDefault(id, NO_ENGAGEMENT)))
                .sorted(Comparator.comparingLong(metric(sort)).reversed()
                        .thenComparing(CreatorVideoStatsResponse::videoId))
                .limit(limit)
                .toList();
    }

    @Override
    public CreatorVideoDetailResponse video(long creatorId, String videoId, int days) {
        if (!repository.ownsVideo(creatorId, videoId)) {
            throw new CreatorVideoNotFoundException(videoId);
        }
        Window window = window(days);
        Scope scope = Scope.video(videoId);

        WatchTotals watches = repository.watchTotals(scope, Instant.EPOCH, window.to()).stream()
                .findFirst().orElse(NO_WATCHES);
        EngagementTotals engagement = repository.engagementTotals(scope, Instant.EPOCH, window.to()).stream()
                .findFirst().orElse(NO_ENGAGEMENT);

        return new CreatorVideoDetailResponse(
                videoId,
                watches.views(),
                watches.viewers(),
                watches.watchedMs(),
                watches.views() == 0 ? 0 : watches.watchedMs() / watches.views(),
                watches.views() == 0 ? 0 : (double) watches.completions() / watches.views(),
                engagement.likes(),
                engagement.comments(),
                engagement.shares(),
                retention(repository.retentionBuckets(videoId)),
                daily(window.firstDay(), window.lastDay(),
                        repository.dailyViews(scope, window.from(), window.to()),
                        repository.dailyEngagement(scope, window.from(), window.to()),
                        List.of()));
    }

    @Override
    public CreatorViewersResponse viewers(long creatorId, int days) {
        Window window = window(days);
        long total = repository.viewers(creatorId, window.from(), window.to());
        long fresh = repository.newViewers(creatorId, window.from(), window.to());
        long previousTotal = repository.viewers(creatorId, window.previousFrom(), window.from());
        long previousFresh = repository.newViewers(creatorId, window.previousFrom(), window.from());

        if (total < minAudience) {
            return new CreatorViewersResponse(total, fresh, previousTotal, previousFresh, false, null, null);
        }
        return new CreatorViewersResponse(total, fresh, previousTotal, previousFresh, true,
                boxed(repository.viewersByHour(creatorId, window.from(), window.to())),
                boxed(repository.viewersByWeekday(creatorId, window.from(), window.to())));
    }

    /** Every day from {@code first} to {@code last}, zeros where nothing happened. */
    static List<CreatorDailyStatsResponse> daily(LocalDate first, LocalDate last, List<DailyViews> views,
                                                 List<DailyEngagement> engagement, List<DailyFollows> follows) {
        Map<LocalDate, DailyViews> viewsByDay = byDay(views, DailyViews::day);
        Map<LocalDate, DailyEngagement> engagementByDay = byDay(engagement, DailyEngagement::day);
        Map<LocalDate, DailyFollows> followsByDay = byDay(follows, DailyFollows::day);

        return first.datesUntil(last.plusDays(1))
                .map(day -> {
                    DailyViews v = viewsByDay.get(day);
                    DailyEngagement e = engagementByDay.get(day);
                    DailyFollows f = followsByDay.get(day);
                    return new CreatorDailyStatsResponse(day,
                            v == null ? 0 : v.views(),
                            e == null ? 0 : e.likes(),
                            e == null ? 0 : e.comments(),
                            e == null ? 0 : e.shares(),
                            f == null ? 0 : f.gained(),
                            f == null ? 0 : f.lost());
                })
                .toList();
    }

    /**
     * Turns "where each view stopped" into "who was still watching here". Point {@code i} is the
     * share of views that stopped in bucket {@code i} or later, i.e. got at least {@code i/20} of
     * the way through.
     */
    static List<Double> retention(long[] buckets) {
        long views = Arrays.stream(buckets).sum();
        if (views == 0) {
            return IntStream.range(0, buckets.length).mapToObj(i -> 0.0).toList();
        }
        Double[] points = new Double[buckets.length];
        long stillWatching = 0;
        for (int i = buckets.length - 1; i >= 0; i--) {
            stillWatching += buckets[i];
            points[i] = (double) stillWatching / views;
        }
        return List.of(points);
    }

    private static CreatorTotalsResponse totals(List<CreatorDailyStatsResponse> days) {
        return new CreatorTotalsResponse(
                sum(days, CreatorDailyStatsResponse::views),
                sum(days, CreatorDailyStatsResponse::likes),
                sum(days, CreatorDailyStatsResponse::comments),
                sum(days, CreatorDailyStatsResponse::shares),
                sum(days, day -> day.followersGained() - day.followersLost()));
    }

    private static long sum(List<CreatorDailyStatsResponse> days, ToLongFunction<CreatorDailyStatsResponse> field) {
        return days.stream().mapToLong(field).sum();
    }

    private static CreatorVideoStatsResponse stats(String videoId, WatchTotals watches, long newViewers,
                                                   EngagementTotals engagement) {
        return new CreatorVideoStatsResponse(videoId, watches.views(), watches.viewers(), newViewers,
                engagement.likes(), engagement.comments(), engagement.shares());
    }

    private static ToLongFunction<CreatorVideoStatsResponse> metric(CreatorVideoSort sort) {
        return switch (sort) {
            case VIEWS -> CreatorVideoStatsResponse::views;
            case VIEWERS -> CreatorVideoStatsResponse::viewers;
            case NEW_VIEWERS -> CreatorVideoStatsResponse::newViewers;
            case LIKES -> CreatorVideoStatsResponse::likes;
            case COMMENTS -> CreatorVideoStatsResponse::comments;
            case SHARES -> CreatorVideoStatsResponse::shares;
        };
    }

    private static <T> Map<LocalDate, T> byDay(List<T> rows, Function<T, LocalDate> day) {
        return rows.stream().collect(Collectors.toMap(day, Function.identity()));
    }

    private static <T> Map<String, T> byVideo(List<T> rows, Function<T, String> videoId) {
        return rows.stream().collect(Collectors.toMap(videoId, Function.identity()));
    }

    private static List<Long> boxed(long[] counts) {
        return Arrays.stream(counts).boxed().toList();
    }
}
