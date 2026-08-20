package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.FeedItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedServiceImplTest {

    private static final Long VIEWER = 7L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private FeedServiceImpl feedService;

    @BeforeEach
    void setUp() {
        feedService = new FeedServiceImpl(redisTemplate);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        givenTrending();
        givenTags();
        givenSeen();
        givenNoQualityData();
    }

    /**
     * A viewer with no history still has to be handed something. Trending is what "popular right
     * now" means when there is nothing personal to go on.
     */
    @Test
    void getFeed_viewerWithNoHistory_fallsBackToTrending() {
        givenTrending(tuple("vid1", 10.0), tuple("vid2", 5.0));

        List<FeedItemResponse> feed = feedService.getFeed(VIEWER, 20);

        assertThat(feed).extracting(FeedItemResponse::videoId).containsExactly("vid1", "vid2");
        assertThat(feed.get(0).reasons()).containsExactly("trending");
    }

    /**
     * The single most visible failure a feed can have. Everything else is a matter of ordering;
     * serving the video someone just finished is the one the user reads as broken.
     */
    @Test
    void getFeed_dropsWhatTheViewerHasAlreadyWatched() {
        givenTrending(tuple("vid1", 10.0), tuple("vid2", 5.0));
        givenSeen("vid1");

        assertThat(feedService.getFeed(VIEWER, 20))
                .extracting(FeedItemResponse::videoId)
                .containsExactly("vid2");
    }

    /**
     * Personalization has to be able to beat raw popularity, or the endpoint is /trending with
     * extra steps: here the tag match outranks a video with ten times the engagement.
     */
    @Test
    void getFeed_aTagTheViewerLikes_outranksAStrongerTrendingHit() {
        givenTrending(tuple("popular", 100.0), tuple("myTaste", 10.0));
        givenTags(tuple("dance", 5.0));
        when(zSetOperations.reverseRange("reco:tag:dance", 0, 99)).thenReturn(Set.of("myTaste"));

        List<FeedItemResponse> feed = feedService.getFeed(VIEWER, 20);

        assertThat(feed).extracting(FeedItemResponse::videoId).containsExactly("myTaste", "popular");
        assertThat(feed.get(0).reasons()).contains("tag:dance", "trending");
    }

    /**
     * A tag the viewer keeps skipping ends up with a negative score. Fetching more of it because
     * it is still in their top five would make the feed worse the more they rejected it.
     */
    @Test
    void getFeed_ignoresTagsTheViewerKeepsSkipping() {
        givenTrending(tuple("vid1", 10.0));
        givenTags(tuple("disliked", -4.0));

        assertThat(feedService.getFeed(VIEWER, 20))
                .extracting(FeedItemResponse::videoId)
                .containsExactly("vid1");
    }

    /**
     * One viewer finishing the only watch a video ever had is a 100% completion rate. Scored
     * literally, every brand-new video would outrank everything that has been measured.
     */
    @Test
    void getFeed_completionRateOnTooFewWatches_isNotTreatedAsQuality() {
        givenTrending(tuple("proven", 10.0), tuple("unmeasured", 10.0));
        when(zSetOperations.score(eq("reco:video:watches"), any(Object[].class)))
                .thenReturn(List.of(20.0, 1.0));
        when(zSetOperations.score(eq("reco:video:completions"), any(Object[].class)))
                .thenReturn(List.of(18.0, 1.0));

        List<FeedItemResponse> feed = feedService.getFeed(VIEWER, 20);

        assertThat(feed).extracting(FeedItemResponse::videoId).containsExactly("proven", "unmeasured");
        assertThat(feed.get(1).score()).isEqualTo(1.5); // 1.0 trending + the neutral 0.5
    }

    @Test
    void getFeed_nothingToRank_returnsEmpty() {
        assertThat(feedService.getFeed(VIEWER, 20)).isEmpty();
    }

    @Test
    void getFeed_honoursTheLimit() {
        givenTrending(tuple("vid1", 10.0), tuple("vid2", 5.0), tuple("vid3", 1.0));

        assertThat(feedService.getFeed(VIEWER, 2)).hasSize(2);
    }

    @SafeVarargs
    private void givenTrending(ZSetOperations.TypedTuple<String>... tuples) {
        when(zSetOperations.reverseRangeWithScores("reco:trending", 0, 299))
                .thenReturn(new LinkedHashSet<>(List.of(tuples)));
    }

    @SafeVarargs
    private void givenTags(ZSetOperations.TypedTuple<String>... tuples) {
        when(zSetOperations.reverseRangeWithScores("reco:user:tags:7", 0, 4))
                .thenReturn(new LinkedHashSet<>(List.of(tuples)));
    }

    private void givenSeen(String... videoIds) {
        when(zSetOperations.range("reco:user:seen:7", 0, -1))
                .thenReturn(new LinkedHashSet<>(List.of(videoIds)));
    }

    private void givenNoQualityData() {
        when(zSetOperations.score(eq("reco:video:watches"), any(Object[].class))).thenReturn(null);
        when(zSetOperations.score(eq("reco:video:completions"), any(Object[].class))).thenReturn(null);
    }

    private ZSetOperations.TypedTuple<String> tuple(String videoId, double score) {
        return ZSetOperations.TypedTuple.of(videoId, score);
    }
}
