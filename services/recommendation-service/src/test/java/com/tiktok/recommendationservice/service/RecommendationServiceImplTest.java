package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.connection.zset.Aggregate;
import org.springframework.data.redis.connection.zset.Weights;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecommendationServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    private RecommendationServiceImpl recommendationService;

    @BeforeEach
    void setUp() {
        recommendationService = new RecommendationServiceImpl(redisTemplate);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
    }

    @Test
    void recordVideoPublished_scoresTheCurrentHourAndIndexesEachTag() {
        recommendationService.recordVideoPublished("vid1", List.of("dance", "food"));

        verify(zSetOperations).incrementScore(startsWith("reco:trend:"), eq("vid1"), eq(1.0));
        verify(setOperations).add("reco:video:tags:vid1", "dance", "food");
        verify(zSetOperations).add(eq("reco:tag:dance"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations).add(eq("reco:tag:food"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void recordVideoPublished_withoutTags_writesNoTagIndex() {
        recommendationService.recordVideoPublished("vid1", List.of());

        verify(setOperations, never()).add(anyString(), anyString());
        verify(zSetOperations, never()).add(startsWith("reco:tag:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    /**
     * Publish time is a ranking feature in its own right, so it has to be recorded for videos
     * with no tags too. Writing it alongside the tag indexes would have skipped exactly those
     * videos, and they would then have been ranked as if they were a day old.
     */
    @Test
    void recordVideoPublished_withoutTags_stillRecordsWhenItWasPublished() {
        recommendationService.recordVideoPublished("vid1", List.of());

        verify(zSetOperations).add(eq("reco:video:published"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void recordVideoDeleted_dropsTheVideoFromTheRankingAndEveryTagIndex() {
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordVideoDeleted("vid1");

        verify(zSetOperations).remove("reco:tag:dance", (Object) "vid1");
        verify(redisTemplate).delete("reco:video:tags:vid1");
        verify(zSetOperations).remove("reco:trending", (Object) "vid1");
        verify(zSetOperations).remove("reco:video:published", (Object) "vid1");
        verify(zSetOperations).remove("reco:video:watches", (Object) "vid1");
        verify(zSetOperations).remove("reco:video:completions", (Object) "vid1");
    }

    /**
     * The ranking is rebuilt from the hourly buckets every minute, so clearing it without
     * clearing them puts the deleted video back within the minute, and keeps doing so for a day.
     */
    @Test
    void recordVideoDeleted_clearsTheHourlyBucketsTheRankingIsRebuiltFrom() {
        when(setOperations.members(anyString())).thenReturn(Set.of());

        recommendationService.recordVideoDeleted("vid1");

        verify(zSetOperations, times(RecoKeys.TRENDING_WINDOW_HOURS))
                .remove(startsWith("reco:trend:"), eq((Object) "vid1"));
    }

    @Test
    void recordLike_unliked_takesTheScoreBackOff() {
        recommendationService.recordLike("vid1", false);

        verify(zSetOperations).incrementScore(startsWith("reco:trend:"), eq("vid1"), eq(-3.0));
    }

    /**
     * Engagement scales with how much of the video was actually watched. A half-watch that
     * counted the same as a full one would make a video that everyone abandons look identical to
     * one that everyone finishes — which is the whole thing this ranking exists to tell apart.
     */
    @Test
    void recordWatch_scoresEngagementByTheWatchedFraction() {
        recommendationService.recordWatch("vid1", 7L, 5_000L, 10_000L, false);

        verify(zSetOperations).incrementScore(startsWith("reco:trend:"), eq("vid1"), eq(1.0));
        verify(zSetOperations).incrementScore("reco:video:watches", "vid1", 1);
        verify(zSetOperations, never()).incrementScore(eq("reco:video:completions"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void recordWatch_completed_countsTowardsTheCompletionRate() {
        recommendationService.recordWatch("vid1", 7L, 10_000L, 10_000L, true);

        verify(zSetOperations).incrementScore("reco:video:completions", "vid1", 1);
    }

    @Test
    void recordWatch_marksTheVideoSeenSoTheFeedStopsOfferingIt() {
        recommendationService.recordWatch("vid1", 7L, 10_000L, 10_000L, true);

        verify(zSetOperations).add(eq("reco:user:seen:7"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void recordWatch_fullWatch_raisesAffinityForEveryTagOnTheVideo() {
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordWatch("vid1", 7L, 10_000L, 10_000L, true);

        verify(zSetOperations).incrementScore("reco:user:tags:7", "dance", 1.0);
    }

    /**
     * A scroll-past is the only negative signal a feed ever gets — nobody presses "not
     * interested". Leaving it at zero would mean a tag the viewer keeps skipping stays exactly
     * as attractive as one they have never been shown.
     */
    @Test
    void recordWatch_aSkip_pushesTheTagAffinityDown() {
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordWatch("vid1", 7L, 500L, 10_000L, false);

        verify(zSetOperations).incrementScore("reco:user:tags:7", "dance", -0.5);
    }

    @Test
    void recordWatch_zeroDuration_doesNotDivideByIt() {
        recommendationService.recordWatch("vid1", 7L, 0L, 0L, false);

        verify(zSetOperations).incrementScore(startsWith("reco:trend:"), eq("vid1"), eq(0.0));
    }

    /**
     * The point of bucketing: the newest hour must outweigh the oldest by a lot, otherwise the
     * ranking is just a lifetime total wearing a window as a disguise.
     */
    @Test
    void rebuildTrending_weightsTheNewestHourFarAboveTheOldest() {
        ArgumentCaptor<Weights> weights = ArgumentCaptor.forClass(Weights.class);

        recommendationService.rebuildTrending();

        verify(zSetOperations).unionAndStore(
                startsWith("reco:trend:"), anyList(), eq("reco:trending"), eq(Aggregate.SUM), weights.capture());

        List<Double> captured = weights.getValue().toList();
        assertThat(captured).hasSize(24);
        assertThat(captured.get(0)).isEqualTo(1.0);
        assertThat(captured.get(23)).isLessThan(0.05);
    }

    @Test
    void getTrending_readsTheDecayedUnionNotTheRawBuckets() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(
                ZSetOperations.TypedTuple.of("vid1", 10.0),
                ZSetOperations.TypedTuple.of("vid2", 5.0)
        ));
        when(zSetOperations.reverseRangeWithScores("reco:trending", 0, 1)).thenReturn(tuples);

        assertThat(recommendationService.getTrending(2)).containsExactly(
                new TrendingVideoResponse("vid1", 10.0),
                new TrendingVideoResponse("vid2", 5.0));
    }

    @Test
    void getTrending_noData_returnsEmptyList() {
        when(zSetOperations.reverseRangeWithScores("reco:trending", 0, 19)).thenReturn(null);

        assertThat(recommendationService.getTrending(20)).isEmpty();
    }
}
