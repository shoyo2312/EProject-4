package com.tiktok.analyticsservice.repository;

import com.tiktok.analyticsservice.dto.response.SearchQueryResponse;
import com.tiktok.analyticsservice.dto.response.TrafficSourceResponse;
import com.tiktok.analyticsservice.repository.CreatorAnalyticsRepository.Scope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Against a real ClickHouse: the owner subquery, the zone the days are cut in and the retention
 * arithmetic are all SQL, and none of it means anything against a mock.
 */
@SpringBootTest(properties = "spring.kafka.listener.auto-startup=false")
@Testcontainers
@ActiveProfiles("test")
class CreatorAnalyticsRepositoryTest {

    private static final long CREATOR = 7L;
    private static final long SOMEONE_ELSE = 8L;

    /** 23:30 on the 22nd in Vietnam, still the 22nd's evening there, already the 22nd in UTC too. */
    private static final Instant EVENING = Instant.parse("2026-10-22T16:30:00Z");
    /** 00:30 on the 23rd in Vietnam — the 22nd in UTC, which is the day a UTC cut would put it on. */
    private static final Instant AFTER_MIDNIGHT = Instant.parse("2026-10-22T17:30:00Z");

    private static final Instant FROM = Instant.parse("2026-10-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-11-01T00:00:00Z");

    @Container
    static final ClickHouseContainer CLICKHOUSE = new ClickHouseContainer("clickhouse/clickhouse-server:24.8-alpine");

    @DynamicPropertySource
    static void clickhouse(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:clickhouse://%s:%d/default?http_connection_provider=HTTP_URL_CONNECTION"
                .formatted(CLICKHOUSE.getHost(), CLICKHOUSE.getMappedPort(8123)));
        registry.add("spring.datasource.username", CLICKHOUSE::getUsername);
        registry.add("spring.datasource.password", CLICKHOUSE::getPassword);
    }

    @Autowired
    private CreatorAnalyticsRepository repository;

    @Autowired
    private EngagementEventRepository engagementEventRepository;

    @Autowired
    private TrainingDataRepository trainingDataRepository;

    @Autowired
    private ViewerProfileRepository viewerProfileRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        for (String table : List.of("watch_events", "engagement_events", "follow_events", "viewer_profiles")) {
            jdbcTemplate.execute("TRUNCATE TABLE " + table);
        }
        engagementEventRepository.insert("pub-mine", "PUBLISHED", "mine", CREATOR, FROM);
        engagementEventRepository.insert("pub-theirs", "PUBLISHED", "theirs", SOMEONE_ELSE, FROM);
    }

    private void watch(String eventId, String videoId, long userId, long watchedMs, long durationMs, Instant at) {
        watch(eventId, videoId, userId, watchedMs, durationMs, at, "OTHER", "");
    }

    private void watch(String eventId, String videoId, long userId, long watchedMs, long durationMs, Instant at,
                       String source, String searchQuery) {
        trainingDataRepository.insertWatches(List.of(new TrainingDataRepository.WatchRow(
                eventId, videoId, userId, watchedMs, durationMs, watchedMs >= durationMs, at, source, searchQuery)));
    }

    @Test
    void aCreatorSeesOnlyTheirOwnVideos() {
        watch("w1", "mine", 100L, 1_000, 10_000, EVENING);
        watch("w2", "theirs", 100L, 1_000, 10_000, EVENING);
        engagementEventRepository.insert("l1", "LIKED", "theirs", 100L, EVENING);

        assertThat(repository.watchTotals(Scope.ownedBy(CREATOR), FROM, TO))
                .extracting(CreatorAnalyticsRepository.WatchTotals::videoId).containsExactly("mine");
        assertThat(repository.engagementTotals(Scope.ownedBy(CREATOR), FROM, TO)).isEmpty();
        assertThat(repository.ownsVideo(CREATOR, "mine")).isTrue();
        assertThat(repository.ownsVideo(CREATOR, "theirs")).isFalse();
    }

    @Test
    void daysAreCutAtVietnamsMidnight() {
        watch("w1", "mine", 100L, 1_000, 10_000, EVENING);
        watch("w2", "mine", 100L, 1_000, 10_000, AFTER_MIDNIGHT);

        assertThat(repository.dailyViews(Scope.ownedBy(CREATOR), FROM, TO))
                .containsExactlyInAnyOrder(
                        new CreatorAnalyticsRepository.DailyViews(LocalDate.of(2026, 10, 22), 1),
                        new CreatorAnalyticsRepository.DailyViews(LocalDate.of(2026, 10, 23), 1));
        assertThat(repository.viewersByHour(CREATOR, FROM, TO)[23]).isEqualTo(1);
        assertThat(repository.viewersByHour(CREATOR, FROM, TO)[0]).isEqualTo(1);
    }

    @Test
    void netLikesAndFollowersCountTheUndo() {
        engagementEventRepository.insert("l1", "LIKED", "mine", 100L, EVENING);
        engagementEventRepository.insert("l2", "LIKED", "mine", 101L, EVENING);
        engagementEventRepository.insert("u1", "UNLIKED", "mine", 100L, EVENING);
        repository.insertFollow("f1", 100L, CREATOR, true, EVENING, "");
        repository.insertFollow("f2", 100L, CREATOR, false, EVENING, "");
        repository.insertFollow("f3", 101L, CREATOR, true, EVENING, "");
        repository.insertFollow("f3", 101L, CREATOR, true, EVENING, "");

        assertThat(repository.dailyEngagement(Scope.ownedBy(CREATOR), FROM, TO))
                .singleElement().satisfies(day -> assertThat(day.likes()).isEqualTo(1));
        assertThat(repository.dailyFollows(CREATOR, FROM, TO))
                .singleElement().satisfies(day -> {
                    assertThat(day.gained()).isEqualTo(2);
                    assertThat(day.lost()).isEqualTo(1);
                });
    }

    @Test
    void aNewViewerIsOneWhoseFirstWatchIsInTheWindow() {
        Instant windowStart = Instant.parse("2026-10-20T00:00:00Z");
        watch("old", "mine", 100L, 1_000, 10_000, Instant.parse("2026-10-05T00:00:00Z"));
        watch("old-again", "mine", 100L, 1_000, 10_000, EVENING);
        watch("new", "mine", 101L, 1_000, 10_000, EVENING);
        watch("anonymous", "mine", 0L, 1_000, 10_000, EVENING);

        assertThat(repository.viewers(CREATOR, windowStart, TO)).isEqualTo(2);
        assertThat(repository.newViewers(CREATOR, windowStart, TO)).isEqualTo(1);
    }

    /** Credited to the first video they watched, once, so the split adds up to the total. */
    @Test
    void aNewViewerCountsForTheVideoTheyCameInThrough() {
        engagementEventRepository.insert("pub-second", "PUBLISHED", "second", CREATOR, FROM);
        Instant windowStart = Instant.parse("2026-10-20T00:00:00Z");
        watch("regular", "mine", 100L, 1_000, 10_000, Instant.parse("2026-10-05T00:00:00Z"));
        watch("regular-again", "second", 100L, 1_000, 10_000, EVENING);
        watch("first", "second", 101L, 1_000, 10_000, EVENING);
        watch("then", "mine", 101L, 1_000, 10_000, AFTER_MIDNIGHT);
        watch("other", "mine", 102L, 1_000, 10_000, EVENING);
        watch("elsewhere", "theirs", 103L, 1_000, 10_000, EVENING);

        assertThat(repository.newViewersByVideo(CREATOR, windowStart, TO))
                .containsExactlyInAnyOrder(
                        new CreatorAnalyticsRepository.NewViewers("second", 1),
                        new CreatorAnalyticsRepository.NewViewers("mine", 1));
        assertThat(repository.newViewers(CREATOR, windowStart, TO)).isEqualTo(2);
    }

    /** A loop reports more time than the video lasts; it still only reached the end. */
    @Test
    void retentionBucketsCapALoopAtTheEnd() {
        watch("half", "mine", 100L, 5_000, 10_000, EVENING);
        watch("loop", "mine", 101L, 25_000, 10_000, EVENING);

        long[] buckets = repository.retentionBuckets("mine");

        assertThat(buckets[10]).isEqualTo(1);
        assertThat(buckets[20]).isEqualTo(1);
    }

    @Test
    void savesAreNetLikeLikes() {
        engagementEventRepository.insert("s1", "SAVED", "mine", 100L, EVENING);
        engagementEventRepository.insert("s2", "SAVED", "mine", 101L, EVENING);
        engagementEventRepository.insert("u1", "UNSAVED", "mine", 100L, EVENING);

        assertThat(repository.engagementTotals(Scope.video("mine"), FROM, TO))
                .singleElement().satisfies(totals -> assertThat(totals.saves()).isEqualTo(1));
        assertThat(repository.dailyEngagement(Scope.video("mine"), FROM, TO))
                .singleElement().satisfies(day -> assertThat(day.saves()).isEqualTo(1));
    }

    /** A follow that came from the video counts even if it was later undone; one from elsewhere does not. */
    @Test
    void followsFromTheVideoAreCountedGross() {
        repository.insertFollow("f1", 100L, CREATOR, true, EVENING, "mine");
        repository.insertFollow("f2", 101L, CREATOR, true, EVENING, "mine");
        repository.insertFollow("f3", 100L, CREATOR, false, EVENING, "");
        repository.insertFollow("f4", 102L, CREATOR, true, EVENING, "");
        // Someone else followed, naming this video: the source is the client's word, not a credit.
        repository.insertFollow("f5", 103L, SOMEONE_ELSE, true, EVENING, "mine");

        assertThat(repository.followsFromVideo(CREATOR, "mine", FROM, TO)).isEqualTo(2);
        assertThat(repository.dailyFollowsFromVideo(CREATOR, "mine", FROM, TO))
                .containsExactly(new CreatorAnalyticsRepository.DailyFollows(LocalDate.of(2026, 10, 22), 2, 0));
    }

    @Test
    void trafficSourcesCountTheWindowsViewsBySource() {
        watch("a", "mine", 100L, 1_000, 10_000, EVENING, "FOR_YOU", "");
        watch("b", "mine", 101L, 1_000, 10_000, EVENING, "FOR_YOU", "");
        watch("c", "mine", 0L, 1_000, 10_000, EVENING, "FOR_YOU", "");
        watch("d", "mine", 102L, 1_000, 10_000, EVENING, "SEARCH", "cat");
        watch("old", "mine", 103L, 1_000, 10_000, Instant.parse("2026-09-01T00:00:00Z"), "PROFILE", "");

        assertThat(repository.trafficSources("mine", FROM, TO))
                .containsExactly(new TrafficSourceResponse("FOR_YOU", 3), new TrafficSourceResponse("SEARCH", 1));
    }

    /** Three distinct searchers, not three views: one person replaying their own search must not be listed. */
    @Test
    void aSearchQueryNeedsThreeSearchers() {
        for (int i = 0; i < 3; i++) {
            watch("a" + i, "mine", 100L + i, 1_000, 10_000, EVENING, "SEARCH", "cat video");
        }
        watch("b0", "mine", 200L, 1_000, 10_000, EVENING, "SEARCH", "dog");
        watch("b1", "mine", 201L, 1_000, 10_000, EVENING, "SEARCH", "dog");
        for (int i = 0; i < 5; i++) {
            watch("c" + i, "mine", 300L, 1_000, 10_000, EVENING, "SEARCH", "nguyen van a wedding");
        }
        watch("blank", "mine", 400L, 1_000, 10_000, EVENING, "SEARCH", "");

        assertThat(repository.searchQueries("mine", FROM, TO, 3, 10))
                .containsExactly(new SearchQueryResponse("cat video", 3));
        assertThat(repository.searchQueries("mine", FROM, TO, 1, 10))
                .extracting(SearchQueryResponse::query).containsExactly("nguyen van a wedding", "cat video", "dog");
    }

    @Test
    void queriesComeMostViewedFirstAndAtMostTen() {
        for (int q = 0; q < 12; q++) {
            for (int i = 0; i <= q; i++) {
                watch("q" + q + "-" + i, "mine", 100L + i, 1_000, 10_000, EVENING, "SEARCH", "query " + q);
            }
        }

        List<SearchQueryResponse> queries = repository.searchQueries("mine", FROM, TO, 1, 10);

        assertThat(queries).hasSize(10);
        assertThat(queries.getFirst()).isEqualTo(new SearchQueryResponse("query 11", 12));
        assertThat(queries).extracting(SearchQueryResponse::views).isSortedAccordingTo((x, y) -> Long.compare(y, x));
    }

    @Test
    void newViewersOfAVideoAreThoseWhoseFirstWatchWasIt() {
        engagementEventRepository.insert("pub-other", "PUBLISHED", "other-mine", CREATOR, FROM);
        Instant windowStart = Instant.parse("2026-10-20T00:00:00Z");
        watch("x-first", "mine", 100L, 1_000, 10_000, EVENING);
        watch("x-then", "other-mine", 100L, 1_000, 10_000, AFTER_MIDNIGHT);
        watch("y-old", "other-mine", 101L, 1_000, 10_000, Instant.parse("2026-10-05T00:00:00Z"));
        watch("y-now", "mine", 101L, 1_000, 10_000, EVENING);

        assertThat(repository.newViewersOfVideo(CREATOR, "mine", windowStart, TO)).isEqualTo(1);
        assertThat(repository.newViewersOfVideo(CREATOR, "other-mine", windowStart, TO)).isZero();
    }

    @Test
    void followerViewersUseTheStateAtTheWindowsEnd() {
        Instant before = Instant.parse("2026-10-10T00:00:00Z");
        Instant after = Instant.parse("2026-11-02T00:00:00Z");
        watch("w1", "mine", 100L, 1_000, 10_000, EVENING);
        watch("w2", "mine", 101L, 1_000, 10_000, EVENING);
        watch("w3", "mine", 102L, 1_000, 10_000, EVENING);
        repository.insertFollow("f1", 100L, CREATOR, true, before, "");
        repository.insertFollow("f2", 100L, CREATOR, false, EVENING, "");
        repository.insertFollow("f3", 101L, CREATOR, true, before, "");
        repository.insertFollow("f4", 101L, CREATOR, false, after, "");
        repository.insertFollow("f5", 102L, SOMEONE_ELSE, true, before, "");

        assertThat(repository.followerViewers(CREATOR, "mine", FROM, TO)).isEqualTo(1);
    }

    /** Full years at the first in-window watch, 22 Oct 2026: a birthday still to come this year is not counted. */
    @Test
    void ageGroupsBucketAtTheFirstInWindowWatch() {
        long user = 100L;
        for (LocalDate birth : List.of(
                LocalDate.of(2008, 11, 1), LocalDate.of(2008, 10, 1),
                LocalDate.of(1991, 11, 1), LocalDate.of(1991, 10, 1),
                LocalDate.of(1971, 11, 1), LocalDate.of(1971, 10, 1))) {
            viewerProfileRepository.upsert(user, birth, EVENING);
            watch("w" + user, "mine", user, 1_000, 10_000, EVENING);
            user++;
        }

        assertThat(repository.viewersByAge("mine", FROM, TO))
                .containsOnly(
                        java.util.Map.entry("13-17", 1L), java.util.Map.entry("18-24", 1L),
                        java.util.Map.entry("25-34", 1L), java.util.Map.entry("35-44", 1L),
                        java.util.Map.entry("45-54", 1L), java.util.Map.entry("55+", 1L));
    }

    @Test
    void viewersWithoutABirthDateAreUnknown() {
        watch("w1", "mine", 100L, 1_000, 10_000, EVENING);
        watch("w2", "mine", 101L, 1_000, 10_000, EVENING);
        watch("w2-again", "mine", 101L, 1_000, 10_000, AFTER_MIDNIGHT);
        watch("anonymous", "mine", 0L, 1_000, 10_000, EVENING);

        assertThat(repository.viewersByAge("mine", FROM, TO)).containsOnly(java.util.Map.entry("UNKNOWN", 2L));
    }
}
