package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.client.RankClient;
import com.tiktok.recommendationservice.client.UserMuteClient;
import com.tiktok.recommendationservice.dto.rank.CandidateFeatures;
import com.tiktok.recommendationservice.dto.response.FeedItemResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedServiceImplTest {

    private static final Long VIEWER = 7L;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    @Mock
    private RankClient rankClient;

    @Mock
    private UserMuteClient userMuteClient;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    /**
     * Always 0.5, so the exploration noise is the same for every candidate and the ordering the
     * existing cases assert is the plain score order.
     */
    private static final RandomGenerator NO_NOISE = () -> Long.MIN_VALUE;

    private FeedServiceImpl feedService;

    @BeforeEach
    void setUp() {
        feedService = new FeedServiceImpl(redisTemplate, rankClient, userMuteClient, NO_NOISE);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        MockPipeline.install(redisTemplate);
        givenTrending();
        givenTags();
        givenSeen();
        givenServed();
        givenNoQualityData();
        givenNoPublishTimes();
        givenCatalogue();
        // The default for every existing case: no model, so the heuristic ordering is asserted.
        when(rankClient.score(any(), any())).thenReturn(Map.of());
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

    /**
     * The point of the whole exercise: when the model has an opinion it decides the order, and
     * it is allowed to disagree with the heuristic completely. Here trending says "popular"
     * first and the model says the opposite.
     */
    @Test
    void getFeed_whenTheModelAnswers_itsScoresDecideTheOrder() {
        givenTrending(tuple("popular", 100.0), tuple("sleeper", 1.0));
        when(rankClient.score(eq(VIEWER), any())).thenReturn(Map.of("sleeper", 0.9, "popular", 0.1));

        List<FeedItemResponse> feed = feedService.getFeed(VIEWER, 20);

        assertThat(feed).extracting(FeedItemResponse::videoId).containsExactly("sleeper", "popular");
        assertThat(feed.get(0).score()).isEqualTo(0.9);
        assertThat(feed.get(0).reasons()).contains("model");
    }

    /**
     * A ranker that is down must cost the feed its ordering, not its contents. Returning nothing
     * here would turn a model outage into an empty For You page.
     */
    @Test
    void getFeed_whenTheRankerIsDown_fallsBackToTheHeuristicOrder() {
        givenTrending(tuple("popular", 100.0), tuple("sleeper", 1.0));
        when(rankClient.score(any(), any())).thenReturn(Map.of());

        List<FeedItemResponse> feed = feedService.getFeed(VIEWER, 20);

        assertThat(feed).extracting(FeedItemResponse::videoId).containsExactly("popular", "sleeper");
        assertThat(feed.get(0).reasons()).doesNotContain("model");
    }

    /**
     * The training/serving contract. The model was fitted on these exact quantities computed
     * from the event log; if the serving side hands it something else under the same names, it
     * still returns confident-looking floats and nothing anywhere reports a fault.
     */
    @Test
    void getFeed_handsTheRankerTheFeaturesRedisActuallyHolds() {
        givenTrending(tuple("vid1", 10.0));
        givenTags(tuple("dance", 3.0));
        when(zSetOperations.reverseRange("reco:tag:dance", 0, 99)).thenReturn(Set.of("vid1"));
        when(zSetOperations.score(eq("reco:video:watches"), any(Object[].class))).thenReturn(List.of(20.0));
        when(zSetOperations.score(eq("reco:video:completions"), any(Object[].class))).thenReturn(List.of(15.0));
        double twoHoursAgo = java.time.Instant.now().getEpochSecond() - 7_200;
        when(zSetOperations.score(eq("reco:video:published"), any(Object[].class)))
                .thenReturn(List.of(twoHoursAgo));

        feedService.getFeed(VIEWER, 20);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CandidateFeatures>> captor = ArgumentCaptor.forClass(List.class);
        verify(rankClient).score(eq(VIEWER), captor.capture());

        CandidateFeatures features = captor.getValue().get(0);
        assertThat(features.videoId()).isEqualTo("vid1");
        assertThat(features.logWatches()).isEqualTo(Math.log1p(20.0));
        assertThat(features.completionRate()).isEqualTo(0.75);
        assertThat(features.ageHours()).isCloseTo(2.0, within(0.01));
        assertThat(features.tagAffinity()).isEqualTo(3.0);
        assertThat(features.tagOverlap()).isEqualTo(1);
    }

    /**
     * How the feed pages without a cursor. The ranking is recomputed every request and the
     * scores move, so an offset into the ranked list points at nothing stable — what makes the
     * next call return different videos is that this call remembers what it handed out.
     */
    @Test
    void getFeed_dropsWhatWasServedRecently_evenThoughItWasNeverWatched() {
        givenTrending(tuple("vid1", 10.0), tuple("vid2", 5.0));
        givenServed("vid1");

        assertThat(feedService.getFeed(VIEWER, 20))
                .extracting(FeedItemResponse::videoId)
                .containsExactly("vid2");
    }

    /**
     * Only what the client actually received is suppressed. Recording the whole candidate pool
     * would burn five hundred videos on one request and empty the feed within a few scrolls.
     */
    @Test
    void getFeed_marksOnlyTheVideosItReturned_notTheWholeCandidatePool() {
        givenTrending(tuple("vid1", 10.0), tuple("vid2", 5.0), tuple("vid3", 1.0));

        feedService.getFeed(VIEWER, 2);

        // One ZADD carrying the whole page rather than one per id — see markServed.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<ZSetOperations.TypedTuple<String>>> served =
                ArgumentCaptor.forClass(Set.class);
        verify(zSetOperations).add(eq("reco:user:served:7"), served.capture());

        assertThat(served.getValue())
                .extracting(ZSetOperations.TypedTuple::getValue)
                .containsExactlyInAnyOrder("vid1", "vid2");
    }

    /**
     * The window covers a scrolling session, so it has to be pushed out while the session is
     * still going. Setting it once would expire the head of the feed under a viewer who is
     * still scrolling through it.
     */
    @Test
    void getFeed_refreshesTheServedWindowOnEveryRequest() {
        givenTrending(tuple("vid1", 10.0));

        feedService.getFeed(VIEWER, 20);

        verify(redisTemplate).expire("reco:user:served:7", RecoKeys.SERVED_TTL);
    }

    /**
     * Muting an account is a promise that its videos stop showing up. The mute used to be stored
     * in user-service and read by nothing, so the muted account went on filling the feed.
     */
    @Test
    void getFeed_dropsVideosByAccountsTheViewerMuted() {
        givenTrending(tuple("fromMuted", 10.0), tuple("fromOther", 5.0));
        when(userMuteClient.mutedIds(VIEWER)).thenReturn(Set.of(9L));
        when(hashOperations.multiGet("reco:video:owner", List.of("fromMuted", "fromOther")))
                .thenReturn(List.of("9", "8"));

        assertThat(feedService.getFeed(VIEWER, 20))
                .extracting(FeedItemResponse::videoId)
                .containsExactly("fromOther");
    }

    /**
     * The catalogue draw reaches every published video, the viewer's own included. On the small
     * instance the draw was added for, the two active viewers are also the main uploaders, so
     * without this they were fed what they had just posted.
     */
    @Test
    void getFeed_dropsTheViewersOwnVideos() {
        givenCatalogue("mine", "theirs");
        when(hashOperations.multiGet("reco:video:owner", List.of("mine", "theirs")))
                .thenReturn(List.of(String.valueOf(VIEWER), "8"));

        assertThat(feedService.getFeed(VIEWER, 20))
                .extracting(FeedItemResponse::videoId)
                .containsExactly("theirs");
    }

    /**
     * The failure the whole feature exists for: trending holds a few dozen videos, the viewer's
     * tags a few dozen more, and after three pages the ranking is spent while hundreds of
     * published videos sit in the catalogue and never get a turn. A random draw from the
     * catalogue is what keeps the feed going past what engagement and taste happen to reach.
     */
    @Test
    void getFeed_whenTrendingAndTagsRunDry_drawsFromTheCatalogue() {
        givenCatalogue("old1", "old2");

        List<FeedItemResponse> feed = feedService.getFeed(VIEWER, 20);

        assertThat(feed).extracting(FeedItemResponse::videoId).containsExactlyInAnyOrder("old1", "old2");
        assertThat(feed.get(0).reasons()).containsExactly("explore");
    }

    /**
     * The published set keeps private, taken-down and held videos (they come back when the
     * reason lifts), so a draw from it has to ask before serving — trending and the tag indexes
     * had those withdrawn, the catalogue has not.
     */
    @Test
    void getFeed_catalogueDraw_skipsHiddenVideos() {
        givenCatalogue("private", "takenDown", "held", "fine");
        when(setOperations.members("reco:video:private")).thenReturn(Set.of("private"));
        when(setOperations.members("reco:video:taken-down")).thenReturn(Set.of("takenDown"));
        when(setOperations.members("reco:video:held")).thenReturn(Set.of("held"));

        assertThat(feedService.getFeed(VIEWER, 20))
                .extracting(FeedItemResponse::videoId)
                .containsExactly("fine");
    }

    /** A video already reached through trending or a tag is not an exploration pick as well. */
    @Test
    void getFeed_catalogueDraw_doesNotDuplicateWhatTrendingAlreadyOffers() {
        givenTrending(tuple("vid1", 10.0));
        givenCatalogue("vid1", "old1");

        List<FeedItemResponse> feed = feedService.getFeed(VIEWER, 20);

        assertThat(feed).extracting(FeedItemResponse::videoId).containsExactlyInAnyOrder("vid1", "old1");
        assertThat(feed.get(0).reasons()).containsExactly("trending");
    }

    /**
     * Why two openings of the app half an hour apart used to show the same page: the score is a
     * deterministic function of state that barely moves, so the ranking was too. A little noise
     * on the way to the sort is what makes the next visit a different one.
     */
    @Test
    void getFeed_doesNotHandTheSameOrderTwice() {
        feedService = new FeedServiceImpl(redisTemplate, rankClient, userMuteClient, new Random(1));
        ZSetOperations.TypedTuple<String>[] trending = new ZSetOperations.TypedTuple[30];
        for (int i = 0; i < trending.length; i++) {
            trending[i] = tuple("vid" + i, 30.0 - i);
        }
        givenTrending(trending);

        List<String> first = feedService.getFeed(VIEWER, 20).stream().map(FeedItemResponse::videoId).toList();
        List<String> second = feedService.getFeed(VIEWER, 20).stream().map(FeedItemResponse::videoId).toList();

        assertThat(first).isNotEqualTo(second);
    }

    private void givenCatalogue(String... videoIds) {
        when(zSetOperations.randomMembers("reco:video:published", 200L)).thenReturn(List.of(videoIds));
        when(setOperations.members(any())).thenReturn(Set.of());
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

    private void givenServed(String... videoIds) {
        when(zSetOperations.range("reco:user:served:7", 0, -1))
                .thenReturn(new LinkedHashSet<>(List.of(videoIds)));
    }

    private void givenSeen(String... videoIds) {
        when(zSetOperations.range("reco:user:seen:7", 0, -1))
                .thenReturn(new LinkedHashSet<>(List.of(videoIds)));
    }

    private void givenNoPublishTimes() {
        when(zSetOperations.score(eq("reco:video:published"), any(Object[].class))).thenReturn(null);
    }

    private void givenNoQualityData() {
        when(zSetOperations.score(eq("reco:video:watches"), any(Object[].class))).thenReturn(null);
        when(zSetOperations.score(eq("reco:video:completions"), any(Object[].class))).thenReturn(null);
    }

    private ZSetOperations.TypedTuple<String> tuple(String videoId, double score) {
        return ZSetOperations.TypedTuple.of(videoId, score);
    }
}
