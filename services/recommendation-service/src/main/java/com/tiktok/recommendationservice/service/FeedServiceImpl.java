package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.client.RankClient;
import com.tiktok.recommendationservice.dto.rank.CandidateFeatures;
import com.tiktok.recommendationservice.dto.response.FeedItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Candidate generation stays rule-based; only the ordering is learned.
 *
 * <p>The split is deliberate. Which videos are considered at all is decided by trending and by
 * the viewer's tags — both readable from Redis, both reconstructible offline, both cheap. How
 * those candidates are ordered is where a model earns its keep, and where being wrong costs an
 * ordering rather than an empty feed. It also sidesteps the failure that kills most first
 * ranking models: trending is a decayed engagement score with no equivalent in the historical
 * event log, so a model trained to lean on it would be trained on a number that does not exist
 * at serving time. Nothing handed to the model here is computed differently in training.
 */
@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    /** How many of the viewer's tags steer the feed. Past a handful, affinity is noise. */
    private static final int TOP_TAGS = 5;

    /** Newest videos pulled per interested tag. */
    private static final long PER_TAG = 100;

    /** How deep into the trending ranking candidate generation reaches. */
    private static final long TRENDING_POOL = 300;

    /** Hard ceiling on scored candidates, so one request is a bounded amount of work. */
    private static final int CANDIDATE_POOL = 500;

    private static final double TREND_WEIGHT = 1.0;
    private static final double AFFINITY_WEIGHT = 2.0;
    private static final double QUALITY_WEIGHT = 1.0;

    /**
     * Completion rate is meaningless on a handful of watches — one viewer finishing the only
     * watch a video ever had is a perfect score and would outrank everything. Below this many
     * watches the video is scored as merely average instead.
     */
    private static final long MIN_WATCHES_FOR_QUALITY = 5;
    private static final double NEUTRAL_QUALITY = 0.5;

    /** Stands in for a video whose publish time has aged out of the trimmed sorted set. */
    private static final double UNKNOWN_AGE_HOURS = 24.0;

    private final StringRedisTemplate redisTemplate;
    private final RankClient rankClient;

    @Override
    public List<FeedItemResponse> getFeed(Long userId, int limit) {
        Map<String, Double> trending = trendingCandidates();
        Map<String, Double> affinityByVideo = new HashMap<>();
        Map<String, Integer> tagOverlap = new HashMap<>();
        Map<String, List<String>> reasons = new HashMap<>();

        for (Map.Entry<String, Double> tag : topTags(userId).entrySet()) {
            for (String videoId : videosTagged(tag.getKey())) {
                affinityByVideo.merge(videoId, tag.getValue(), Double::sum);
                tagOverlap.merge(videoId, 1, Integer::sum);
                reasons.computeIfAbsent(videoId, id -> new ArrayList<>()).add("tag:" + tag.getKey());
            }
        }
        trending.keySet().forEach(videoId ->
                reasons.computeIfAbsent(videoId, id -> new ArrayList<>()).add("trending"));

        // Tag matches first, so that when the pool is capped it is the trending tail that gets
        // cut rather than the videos this viewer is actually interested in — and the tag matches
        // themselves strongest first, because affinityByVideo is a HashMap and its iteration order
        // is the hash layout. Taking them as they come makes which five hundred survive the cap an
        // accident, and drops a viewer's best matches ahead of their weakest ones.
        Set<String> pool = affinityByVideo.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // Trending comes out of trendingCandidates in rank order, which this set preserves.
        pool.addAll(trending.keySet());

        Set<String> excluded = alreadyDelivered(userId);
        List<String> candidates = pool.stream()
                .filter(videoId -> !excluded.contains(videoId))
                .limit(CANDIDATE_POOL)
                .toList();

        if (candidates.isEmpty()) {
            return List.of();
        }

        Counters counters = counters(candidates);
        Map<String, Double> ages = ageHours(candidates);

        Map<String, Double> modelScores = rankClient.score(userId,
                features(candidates, counters, ages, affinityByVideo, tagOverlap));

        Map<String, Double> scores = modelScores.isEmpty()
                ? heuristicScores(candidates, trending, affinityByVideo, counters)
                : modelScores;
        if (!modelScores.isEmpty()) {
            candidates.forEach(videoId -> reasons.computeIfAbsent(videoId, id -> new ArrayList<>()).add("model"));
        }

        List<FeedItemResponse> feed = candidates.stream()
                .map(videoId -> new FeedItemResponse(
                        videoId,
                        round(scores.getOrDefault(videoId, 0.0)),
                        reasons.getOrDefault(videoId, List.of())))
                .sorted(Comparator.comparingDouble(FeedItemResponse::score).reversed())
                .limit(limit)
                .toList();

        markServed(userId, feed);
        return feed;
    }

    private List<CandidateFeatures> features(List<String> candidates,
                                             Counters counters,
                                             Map<String, Double> ages,
                                             Map<String, Double> affinityByVideo,
                                             Map<String, Integer> tagOverlap) {
        return candidates.stream()
                .map(videoId -> new CandidateFeatures(
                        videoId,
                        Math.log1p(counters.watches().getOrDefault(videoId, 0.0)),
                        counters.completionRates().getOrDefault(videoId, NEUTRAL_QUALITY),
                        ages.getOrDefault(videoId, UNKNOWN_AGE_HOURS),
                        affinityByVideo.getOrDefault(videoId, 0.0),
                        tagOverlap.getOrDefault(videoId, 0)))
                .toList();
    }

    /**
     * The ordering that shipped before there was a model, kept as the fallback rather than
     * deleted: it is what the feed serves whenever the ranker is down or has no model file yet,
     * which includes every deployment made before the first training run.
     */
    private Map<String, Double> heuristicScores(List<String> candidates,
                                                Map<String, Double> trending,
                                                Map<String, Double> affinityByVideo,
                                                Counters counters) {
        double maxTrend = max(trending.values());
        double maxAffinity = max(affinityByVideo.values());

        Map<String, Double> scores = new HashMap<>();
        for (String videoId : candidates) {
            scores.put(videoId,
                    TREND_WEIGHT * trending.getOrDefault(videoId, 0.0) / maxTrend
                            + AFFINITY_WEIGHT * affinityByVideo.getOrDefault(videoId, 0.0) / maxAffinity
                            + QUALITY_WEIGHT * counters.completionRates().getOrDefault(videoId, NEUTRAL_QUALITY));
        }
        return scores;
    }

    private Map<String, Double> trendingCandidates() {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(RecoKeys.TRENDING, 0, TRENDING_POOL - 1);
        if (tuples == null) {
            return Map.of();
        }
        // Insertion-ordered so the candidate list is trending rank order, which makes both the
        // pool cap and the response deterministic instead of dependent on hash layout.
        Map<String, Double> scores = new LinkedHashMap<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null && tuple.getScore() != null && tuple.getScore() > 0) {
                scores.put(tuple.getValue(), tuple.getScore());
            }
        }
        return scores;
    }

    /** Only tags the viewer is positive about — a tag they keep skipping must not fetch more. */
    private Map<String, Double> topTags(Long userId) {
        Set<ZSetOperations.TypedTuple<String>> tuples = redisTemplate.opsForZSet()
                .reverseRangeWithScores(RecoKeys.userTags(userId), 0, TOP_TAGS - 1L);
        if (tuples == null) {
            return Map.of();
        }
        Map<String, Double> tags = new HashMap<>();
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() != null && tuple.getScore() != null && tuple.getScore() > 0) {
                tags.put(tuple.getValue(), tuple.getScore());
            }
        }
        return tags;
    }

    private Set<String> videosTagged(String tag) {
        Set<String> videoIds = redisTemplate.opsForZSet().reverseRange(RecoKeys.tagIndex(tag), 0, PER_TAG - 1);
        return videoIds == null ? Set.of() : videoIds;
    }

    /**
     * Everything this viewer must not be handed again: what they watched, plus what they were
     * offered in the last half hour. Reading both is what lets the client page through the feed
     * by simply asking again — see {@link RecoKeys#userServed} for why a cursor does not work
     * here and why the two sets are not one.
     */
    private Set<String> alreadyDelivered(Long userId) {
        Set<String> excluded = new HashSet<>();
        Set<String> seen = redisTemplate.opsForZSet().range(RecoKeys.userSeen(userId), 0, -1);
        if (seen != null) {
            excluded.addAll(seen);
        }
        Set<String> served = redisTemplate.opsForZSet().range(RecoKeys.userServed(userId), 0, -1);
        if (served != null) {
            excluded.addAll(served);
        }
        return excluded;
    }

    /**
     * Records only what the client was actually handed, not the whole candidate pool. Suppressing
     * five hundred videos because one request ranked them would empty the feed within a few
     * scrolls.
     */
    private void markServed(Long userId, List<FeedItemResponse> feed) {
        if (feed.isEmpty()) {
            return;
        }
        String key = RecoKeys.userServed(userId);
        double now = Instant.now().getEpochSecond();
        // One ZADD for the page rather than one per item, same reasoning as the two ZMSCOREs in
        // counters(): this runs on every feed request, and fifty round trips to write fifty ids is
        // latency spent on bookkeeping the viewer is waiting through.
        Set<ZSetOperations.TypedTuple<String>> served = feed.stream()
                .map(item -> (ZSetOperations.TypedTuple<String>)
                        new DefaultTypedTuple<>(item.videoId(), now))
                .collect(Collectors.toSet());
        redisTemplate.opsForZSet().add(key, served);
        redisTemplate.opsForZSet().removeRange(key, 0, -RecoKeys.TRIM_TO - 1);
        // Refreshed on every request on purpose: the window is meant to cover a scrolling
        // session, and a session that is still going should not have its head expire under it.
        redisTemplate.expire(key, RecoKeys.SERVED_TTL);
    }

    /** Raw watch counts alongside the completion rate, because the model wants both. */
    private record Counters(Map<String, Double> watches, Map<String, Double> completionRates) {
    }

    /**
     * Two ZMSCORE calls for the whole candidate set rather than a round trip per video: at 500
     * candidates the difference is a feed that answers in two hops and one that answers in a
     * thousand.
     */
    private Counters counters(List<String> videoIds) {
        Object[] members = videoIds.toArray();
        List<Double> watches = redisTemplate.opsForZSet().score(RecoKeys.VIDEO_WATCHES, members);
        List<Double> completions = redisTemplate.opsForZSet().score(RecoKeys.VIDEO_COMPLETIONS, members);
        if (watches == null || completions == null) {
            return new Counters(Map.of(), Map.of());
        }

        Map<String, Double> watchCounts = new HashMap<>();
        Map<String, Double> rates = new HashMap<>();
        for (int i = 0; i < videoIds.size(); i++) {
            Double watched = watches.get(i);
            if (watched == null) {
                continue;
            }
            watchCounts.put(videoIds.get(i), watched);
            if (watched < MIN_WATCHES_FOR_QUALITY) {
                continue;
            }
            Double completed = completions.get(i);
            rates.put(videoIds.get(i), (completed == null ? 0.0 : completed) / watched);
        }
        return new Counters(watchCounts, rates);
    }

    private Map<String, Double> ageHours(List<String> videoIds) {
        List<Double> publishedAt = redisTemplate.opsForZSet().score(RecoKeys.VIDEO_PUBLISHED, videoIds.toArray());
        if (publishedAt == null) {
            return Map.of();
        }
        double now = Instant.now().getEpochSecond();
        Map<String, Double> ages = new HashMap<>();
        for (int i = 0; i < videoIds.size(); i++) {
            Double published = publishedAt.get(i);
            if (published != null) {
                ages.put(videoIds.get(i), Math.max(0.0, (now - published) / 3600.0));
            }
        }
        return ages;
    }

    /** Guards the normalization divide: an empty or all-zero source contributes nothing. */
    private double max(Collection<Double> values) {
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        return max > 0 ? max : 1.0;
    }

    private double round(double score) {
        return Math.round(score * 10_000.0) / 10_000.0;
    }
}
