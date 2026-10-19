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
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RecommendationServiceImpl recommendationService;

    @BeforeEach
    void setUp() {
        recommendationService = new RecommendationServiceImpl(redisTemplate);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
        when(redisTemplate.opsForSet()).thenReturn(setOperations);
        when(redisTemplate.<Object, Object>opsForHash()).thenReturn(hashOperations);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        MockPipeline.install(redisTemplate);
    }

    /**
     * The feed drops videos by muted accounts, and the owner is the one thing it cannot learn
     * anywhere else. Recorded for untagged videos too — a mute is about who, not what.
     */
    @Test
    void recordVideoUploaded_remembersWhoOwnsTheVideo_evenWithoutTags() {
        recommendationService.recordVideoUploaded("vid1", 9L, "PUBLIC", List.of());

        verify(hashOperations).put("reco:video:owner", "vid1", "9");
    }

    @Test
    void recordVideoDeleted_forgetsTheOwner() {
        recommendationService.recordVideoDeleted("vid1");

        verify(hashOperations).delete("reco:video:owner", "vid1");
        verify(setOperations).remove("reco:video:held", "vid1");
    }

    /**
     * The publication event fires while the video is still PROCESSING, so nothing it triggers may
     * make the video reachable from the feed. Only the tags are kept, for the readiness event to
     * index later.
     */
    @Test
    void recordVideoUploaded_stashesTheTagsWithoutIndexingAnything() {
        // Explicit: a Mockito mock answers 0.0 for a Double, not null, and 0.0 would read as a
        // video that is already ready.
        when(zSetOperations.score("reco:video:published", "vid1")).thenReturn(null);

        recommendationService.recordVideoUploaded("vid1", 9L, "PUBLIC", List.of("dance", "food"));

        verify(setOperations).add("reco:video:tags:vid1", "dance", "food");
        verify(zSetOperations, never()).add(startsWith("reco:tag:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations, never()).add(eq("reco:video:published"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations, never()).incrementScore(startsWith("reco:trend:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    /**
     * The two events arrive on different topics, so a lagging publication can land after the
     * transcode result. The tags still have to reach the indexes, or the video stays out of
     * every tag index it belongs in for as long as it exists.
     */
    @Test
    void recordVideoUploaded_whenTheVideoIsAlreadyReady_indexesTheTagsAtItsPublishTime() {
        when(zSetOperations.score("reco:video:published", "vid1")).thenReturn(1700.0);

        recommendationService.recordVideoUploaded("vid1", 9L, "PUBLIC", List.of("dance"));

        verify(zSetOperations).add("reco:tag:dance", "vid1", 1700.0);
    }

    @Test
    void recordVideoReady_scoresTheCurrentHourAndIndexesEachStashedTag() {
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordVideoReady("vid1");

        verify(zSetOperations).incrementScore(startsWith("reco:trend:"), eq("vid1"), eq(1.0));
        verify(zSetOperations).add(eq("reco:tag:dance"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
    }

    /**
     * Publish time is a ranking feature in its own right, so it has to be recorded for videos
     * with no tags too. Writing it alongside the tag indexes would have skipped exactly those
     * videos, and they would then have been ranked as if they were a day old.
     */
    @Test
    void recordVideoReady_withoutTags_stillRecordsWhenItWasPublished() {
        recommendationService.recordVideoReady("vid1");

        verify(zSetOperations).add(eq("reco:video:published"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations, never()).add(startsWith("reco:tag:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
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
     * A failed transcode can still be rescued by a redelivered publication, and the rescue comes
     * back through recordVideoReady. A tombstone would turn it away for eight days, and erasing the
     * private flag would let a private video into trending once it got in.
     */
    @Test
    void recordTranscodeFailed_withdrawsTheVideo_butLeavesNoTombstoneAndKeepsTheStash() {
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordTranscodeFailed("vid1");

        verify(zSetOperations).remove("reco:tag:dance", (Object) "vid1");
        verify(zSetOperations).remove("reco:trending", (Object) "vid1");
        verify(valueOperations, never()).set(eq("reco:video:deleted:vid1"), anyString(), any(java.time.Duration.class));
        verify(redisTemplate, never()).delete("reco:video:tags:vid1");
        verify(hashOperations, never()).delete(eq("reco:video:owner"), any());
        verify(setOperations, never()).remove(eq("reco:video:private"), any());
    }

    /**
     * A takedown hid the video everywhere except here: it stayed in trending and the tag indexes,
     * so the feed kept handing out an id that hydrates to nothing and costs the viewer a slot.
     */
    @Test
    void recordTakenDown_withdrawsTheVideoFromEverythingCandidateGenerationReads() {
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordTakenDown("vid1");

        verify(setOperations).add("reco:video:taken-down", "vid1");
        verify(zSetOperations).remove("reco:tag:dance", (Object) "vid1");
        verify(zSetOperations).remove("reco:trending", (Object) "vid1");
        verify(zSetOperations, times(RecoKeys.TRENDING_WINDOW_HOURS))
                .remove(startsWith("reco:trend:"), eq((Object) "vid1"));
        // Kept, unlike a deletion: a restore has to be able to put the video back.
        verify(redisTemplate, never()).delete("reco:video:tags:vid1");
    }

    /**
     * A deletion clears every other marker the video had, so it leaves one of its own — outliving
     * the broker's retention, since that is how late an event about the video can still arrive.
     */
    @Test
    void recordVideoDeleted_leavesATombstoneThatOutlivesAnyDelivery() {
        recommendationService.recordVideoDeleted("vid1");

        verify(valueOperations).set("reco:video:deleted:vid1", "1", RecoKeys.DELETED_TTL);
        assertThat(RecoKeys.DELETED_TTL).isGreaterThan(java.time.Duration.ofDays(7));
    }

    /**
     * Likes arrive on another topic than the deletion, so one consumed late landed in the current
     * hour's bucket and the next rebuild put the deleted video back into trending for a day.
     */
    @Test
    void engagementArrivingAfterTheDeletion_doesNotPutTheVideoBackIntoTrending() {
        when(redisTemplate.hasKey("reco:video:deleted:vid1")).thenReturn(true);

        recommendationService.recordLike("vid1", true);
        recommendationService.recordComment("vid1", true);

        verify(zSetOperations, never()).incrementScore(startsWith("reco:trend:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    /**
     * A video deleted while it was still transcoding or in moderation gets its verdict afterwards,
     * and an approval scored and indexed it as a fresh publication.
     */
    @Test
    void recordVideoReady_forADeletedVideo_recordsNothing() {
        when(redisTemplate.hasKey("reco:video:deleted:vid1")).thenReturn(true);
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordVideoReady("vid1");

        verify(zSetOperations, never()).add(anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations, never()).incrementScore(anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    /** Otherwise the owner's own likes on a hidden video walk it back into trending. */
    @Test
    void engagementOnAHiddenVideo_doesNotPutItBackIntoTrending() {
        when(setOperations.isMember("reco:video:taken-down", "vid1")).thenReturn(true);

        recommendationService.recordLike("vid1", true);

        verify(zSetOperations, never()).incrementScore(startsWith("reco:trend:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    @Test
    void recordRestored_putsTheVideoBackUnderItsTagsAtItsOriginalPublishTime() {
        when(setOperations.remove("reco:video:taken-down", "vid1")).thenReturn(1L);
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));
        when(zSetOperations.score("reco:video:published", "vid1")).thenReturn(1700.0);

        recommendationService.recordRestored("vid1", Instant.now());

        verify(zSetOperations).add("reco:tag:dance", "vid1", 1700.0);
    }

    /** Restoring a takedown must not undo the owner making the video private. */
    @Test
    void recordRestored_ofAPrivateVideo_keepsItOutOfTheFeed() {
        when(setOperations.remove("reco:video:taken-down", "vid1")).thenReturn(1L);
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));
        when(zSetOperations.score("reco:video:published", "vid1")).thenReturn(1700.0);
        when(setOperations.isMember("reco:video:private", "vid1")).thenReturn(true);

        recommendationService.recordRestored("vid1", Instant.now());

        verify(zSetOperations, never()).add(startsWith("reco:tag:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    /**
     * A verdict other than APPROVED used to run the deletion cleanup, which threw away the tags
     * and owner that an admin's approval later needs to index the video.
     */
    @Test
    void recordHeld_withdrawsTheVideoButKeepsWhatAnApprovalNeeds() {
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordHeld("vid1", Instant.now());

        verify(setOperations).add("reco:video:held", "vid1");
        verify(zSetOperations).remove("reco:tag:dance", (Object) "vid1");
        verify(zSetOperations).remove("reco:trending", (Object) "vid1");
        verify(redisTemplate, never()).delete("reco:video:tags:vid1");
        verify(hashOperations, never()).delete("reco:video:owner", "vid1");
    }

    /** The owner watching and liking their own held video must not walk it into trending. */
    @Test
    void engagementOnAHeldVideo_doesNotPutItIntoTrending() {
        when(setOperations.isMember("reco:video:held", "vid1")).thenReturn(true);

        recommendationService.recordLike("vid1", true);

        verify(zSetOperations, never()).incrementScore(startsWith("reco:trend:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    /**
     * An admin approving a held video: it goes into the feed the way an APPROVED verdict would
     * have put it there, under its stashed tags and published as of now.
     */
    @Test
    void recordRestored_ofAHeldVideo_putsItIntoTheFeed() {
        when(setOperations.remove("reco:video:held", "vid1")).thenReturn(1L);
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordRestored("vid1", Instant.now());

        verify(zSetOperations).add(eq("reco:video:published"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations).add(eq("reco:tag:dance"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations).incrementScore(startsWith("reco:trend:"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
    }

    /**
     * Taken down while it waited for review: the restore lifts the takedown and nothing more,
     * as video-service returns it to PENDING_REVIEW. Approving it takes a second restore.
     */
    @Test
    void recordRestored_ofAHeldVideoThatWasTakenDown_onlyLiftsTheTakedown() {
        when(setOperations.remove("reco:video:taken-down", "vid1")).thenReturn(1L);
        when(setOperations.isMember("reco:video:held", "vid1")).thenReturn(true);

        recommendationService.recordRestored("vid1", Instant.now());

        verify(setOperations, never()).remove("reco:video:held", "vid1");
        verify(zSetOperations, never()).add(anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    /** Neither down nor held: already live, or never heard of. Nothing to undo either way. */
    @Test
    void recordRestored_withNothingToUndo_indexesNothing() {
        recommendationService.recordRestored("vid1", Instant.now());

        verify(zSetOperations, never()).add(anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations, never()).incrementScore(anyString(), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    /**
     * Nothing to undo may only mean the verdict has not been read yet — this consumer lagging on
     * the moderation topic. The time of the decision is kept for that verdict to be held against.
     */
    @Test
    void recordRestored_beforeAnyVerdict_remembersWhenTheAdminDecided() {
        Instant restoredAt = Instant.ofEpochMilli(1_700_000_000_000L);

        recommendationService.recordRestored("vid1", restoredAt);

        verify(valueOperations).set("reco:video:approved-at:vid1", "1700000000000", RecoKeys.DELETED_TTL);
    }

    /**
     * The approval was read before the verdict it overruled. The verdict used to hold the video
     * for good: approved and published in video-service, never in the feed.
     */
    @Test
    void recordHeld_afterAnApprovalThatOverruledIt_putsTheVideoInTheFeed() {
        Instant checkedAt = Instant.ofEpochMilli(1_700_000_000_000L);
        when(valueOperations.get("reco:video:approved-at:vid1"))
                .thenReturn(String.valueOf(checkedAt.plusSeconds(30).toEpochMilli()));
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordHeld("vid1", checkedAt);

        verify(setOperations, never()).add("reco:video:held", "vid1");
        verify(zSetOperations).add(eq("reco:video:published"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations).add(eq("reco:tag:dance"), eq("vid1"), org.mockito.ArgumentMatchers.anyDouble());
    }

    /** A restore from before the verdict existed approved nothing, so the verdict still holds the video. */
    @Test
    void recordHeld_afterARestoreFromBeforeTheVerdict_stillHoldsIt() {
        Instant checkedAt = Instant.ofEpochMilli(1_700_000_000_000L);
        when(valueOperations.get("reco:video:approved-at:vid1"))
                .thenReturn(String.valueOf(checkedAt.minusSeconds(30).toEpochMilli()));

        recommendationService.recordHeld("vid1", checkedAt);

        verify(setOperations).add("reco:video:held", "vid1");
    }

    /**
     * Visibility rode only on the publication, so a video made PRIVATE or FRIENDS afterwards kept
     * being recommended to everyone.
     */
    @Test
    void recordVisibilityChanged_toPrivate_withdrawsTheVideo() {
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordVisibilityChanged("vid1", "FRIENDS");

        verify(setOperations).add("reco:video:private", "vid1");
        verify(zSetOperations).remove("reco:tag:dance", (Object) "vid1");
        verify(zSetOperations).remove("reco:trending", (Object) "vid1");
    }

    @Test
    void recordVisibilityChanged_backToPublic_reindexesTheVideo() {
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));
        when(zSetOperations.score("reco:video:published", "vid1")).thenReturn(1700.0);

        recommendationService.recordVisibilityChanged("vid1", "PUBLIC");

        verify(setOperations).remove("reco:video:private", "vid1");
        verify(zSetOperations).add("reco:tag:dance", "vid1", 1700.0);
    }

    /** A video uploaded PRIVATE was recommended the moment moderation approved it. */
    @Test
    void aVideoUploadedPrivate_isNotIndexedWhenItBecomesReady() {
        // What the SADD in recordVideoUploaded leaves behind in real Redis.
        when(setOperations.isMember("reco:video:private", "vid1")).thenReturn(true);
        recommendationService.recordVideoUploaded("vid1", 9L, "PRIVATE", List.of("dance"));
        when(setOperations.members("reco:video:tags:vid1")).thenReturn(Set.of("dance"));

        recommendationService.recordVideoReady("vid1");

        verify(setOperations).add("reco:video:private", "vid1");
        verify(zSetOperations, never()).add(startsWith("reco:tag:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
        verify(zSetOperations, never()).incrementScore(startsWith("reco:trend:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
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

    /** Sixty shares an hour from one account used to be worth a hundred viewers' likes. */
    @Test
    void recordShare_scoresOnlyAViewersFirstShareOfTheVideo() {
        when(valueOperations.setIfAbsent(eq("reco:shared:vid1:9"), any(), any(java.time.Duration.class)))
                .thenReturn(true, false);

        recommendationService.recordShare("vid1", 9L);
        recommendationService.recordShare("vid1", 9L);

        verify(zSetOperations, times(1)).incrementScore(startsWith("reco:trend:"), eq("vid1"), eq(5.0));
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
        viewersBestWatchRisesBy("0.5");

        recommendationService.recordWatch("vid1", 7L, 5_000L, 10_000L, false);

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("reco:watched:vid1:7")), eq("0.5"), any());
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

        verify(zSetOperations, never()).incrementScore(startsWith("reco:trend:"), anyString(), org.mockito.ArgumentMatchers.anyDouble());
    }

    /**
     * The share loophole, on watches: a completed watch reported sixty times an hour by one
     * account was sixty full scores. Past the viewer's best, a rewatch adds nothing to trending.
     */
    @Test
    void recordWatch_aViewersRewatchAddsNothingToTrending() {
        viewersBestWatchRisesBy("1.0", "0");

        recommendationService.recordWatch("vid1", 7L, 10_000L, 10_000L, true);
        recommendationService.recordWatch("vid1", 7L, 10_000L, 10_000L, true);

        verify(zSetOperations, times(1)).incrementScore(startsWith("reco:trend:"), eq("vid1"), eq(2.0));
        // The model's features still see every session.
        verify(zSetOperations, times(2)).incrementScore("reco:video:watches", "vid1", 1);
    }

    /** A skip first and a real watch later is still one real watch, not one skip. */
    @Test
    void recordWatch_aLaterFullerWatchCountsTheDifference() {
        viewersBestWatchRisesBy("0.1", "0.9");

        recommendationService.recordWatch("vid1", 7L, 1_000L, 10_000L, false);
        recommendationService.recordWatch("vid1", 7L, 10_000L, 10_000L, true);

        verify(zSetOperations).incrementScore(startsWith("reco:trend:"), eq("vid1"), eq(0.2));
        verify(zSetOperations).incrementScore(startsWith("reco:trend:"), eq("vid1"), eq(1.8));
    }

    @SuppressWarnings("unchecked")
    private void viewersBestWatchRisesBy(String first, String... then) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(first, then);
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
