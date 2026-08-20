package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.FeedItemResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private final StringRedisTemplate redisTemplate;

    @Override
    public List<FeedItemResponse> getFeed(Long userId, int limit) {
        Map<String, Double> trending = trendingCandidates();
        Map<String, Double> affinityByVideo = new HashMap<>();
        Map<String, List<String>> reasons = new HashMap<>();

        for (Map.Entry<String, Double> tag : topTags(userId).entrySet()) {
            for (String videoId : videosTagged(tag.getKey())) {
                affinityByVideo.merge(videoId, tag.getValue(), Double::sum);
                reasons.computeIfAbsent(videoId, id -> new ArrayList<>()).add("tag:" + tag.getKey());
            }
        }
        trending.keySet().forEach(videoId ->
                reasons.computeIfAbsent(videoId, id -> new ArrayList<>()).add("trending"));

        // Tag matches first, so that when the pool is capped it is the trending tail that gets
        // cut rather than the videos this viewer is actually interested in.
        Set<String> pool = new LinkedHashSet<>(affinityByVideo.keySet());
        pool.addAll(trending.keySet());

        Set<String> seen = seen(userId);
        List<String> candidates = pool.stream()
                .filter(videoId -> !seen.contains(videoId))
                .limit(CANDIDATE_POOL)
                .toList();

        if (candidates.isEmpty()) {
            return List.of();
        }

        double maxTrend = max(trending.values());
        double maxAffinity = max(affinityByVideo.values());
        Map<String, Double> quality = completionRates(candidates);

        return candidates.stream()
                .map(videoId -> new FeedItemResponse(
                        videoId,
                        round(TREND_WEIGHT * trending.getOrDefault(videoId, 0.0) / maxTrend
                                + AFFINITY_WEIGHT * affinityByVideo.getOrDefault(videoId, 0.0) / maxAffinity
                                + QUALITY_WEIGHT * quality.getOrDefault(videoId, NEUTRAL_QUALITY)),
                        reasons.getOrDefault(videoId, List.of())))
                .sorted(Comparator.comparingDouble(FeedItemResponse::score).reversed())
                .limit(limit)
                .toList();
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

    private Set<String> seen(Long userId) {
        Set<String> videoIds = redisTemplate.opsForZSet().range(RecoKeys.userSeen(userId), 0, -1);
        return videoIds == null ? Set.of() : videoIds;
    }

    /**
     * Two ZMSCORE calls for the whole candidate set rather than a round trip per video: at 500
     * candidates the difference is a feed that answers in one hop and one that answers in a
     * thousand.
     */
    private Map<String, Double> completionRates(List<String> videoIds) {
        Object[] members = videoIds.toArray();
        List<Double> watches = redisTemplate.opsForZSet().score(RecoKeys.VIDEO_WATCHES, members);
        List<Double> completions = redisTemplate.opsForZSet().score(RecoKeys.VIDEO_COMPLETIONS, members);
        if (watches == null || completions == null) {
            return Map.of();
        }

        Map<String, Double> rates = new HashMap<>();
        for (int i = 0; i < videoIds.size(); i++) {
            Double watched = watches.get(i);
            if (watched == null || watched < MIN_WATCHES_FOR_QUALITY) {
                continue;
            }
            Double completed = completions.get(i);
            rates.put(videoIds.get(i), (completed == null ? 0.0 : completed) / watched);
        }
        return rates;
    }

    /** Guards the normalization divide: an empty or all-zero source contributes nothing. */
    private double max(java.util.Collection<Double> values) {
        double max = values.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        return max > 0 ? max : 1.0;
    }

    private double round(double score) {
        return Math.round(score * 10_000.0) / 10_000.0;
    }
}
