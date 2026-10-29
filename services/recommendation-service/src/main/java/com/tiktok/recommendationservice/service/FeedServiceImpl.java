package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.client.RankClient;
import com.tiktok.recommendationservice.client.UserMuteClient;
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
import java.util.random.RandomGenerator;
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

    /**
     * Random draw from the whole published catalogue, each request. Trending and the viewer's
     * tags between them reach a few dozen videos on a small instance, and once those are seen or
     * served the ranking was spent while hundreds of videos sat unreachable — the client then
     * fell through to the chronological feed, which is the same list every time.
     */
    private static final long EXPLORE_POOL = 200;

    /** Hard ceiling on scored candidates, so one request is a bounded amount of work. */
    private static final int CANDIDATE_POOL = 500;

    /**
     * Gumbel noise added to each score before the sort, as a fraction of the spread between the
     * best and worst candidate. The score itself is a deterministic function of state that barely
     * moves between two openings of the app, so without this the page was the same both times.
     * Gumbel rather than uniform so the perturbed order is a sample proportional to the scores:
     * the best candidate is still the likeliest to come first, just not certain to.
     */
    private static final double EXPLORATION_TEMPERATURE = 0.25;

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

    /**
     * Stands in for a video whose publish time has aged out of the trimmed sorted set. Must match
     * UNKNOWN_AGE_HOURS in rank-service's features.py, which training fills the same gap with —
     * two different fillers make age_hours mean one thing in training and another when served.
     */
    private static final double UNKNOWN_AGE_HOURS = 24.0;

    private final StringRedisTemplate redisTemplate;
    private final RankClient rankClient;
    private final UserMuteClient userMuteClient;
    private final RandomGenerator random;

    @Override
    public List<FeedItemResponse> getFeed(Long userId, int limit) {
        // Everything the feed reads about this viewer before it has candidates, in one round trip.
        List<Object> reads = RedisPipeline.run(redisTemplate, ops -> {
            ops.opsForZSet().reverseRangeWithScores(RecoKeys.TRENDING, 0, TRENDING_POOL - 1);
            ops.opsForZSet().reverseRangeWithScores(RecoKeys.userTags(userId), 0, TOP_TAGS - 1L);
            ops.opsForZSet().range(RecoKeys.userSeen(userId), 0, -1);
            ops.opsForZSet().range(RecoKeys.userServed(userId), 0, -1);
            ops.opsForZSet().randomMembers(RecoKeys.VIDEO_PUBLISHED, EXPLORE_POOL);
            // The published set keeps private, taken-down and held videos (they come back when
            // the reason lifts), so unlike the indexes a draw from it has to be checked. Whole
            // sets rather than SMISMEMBER per draw: that operation reads its own reply and
            // cannot run inside a pipeline, and these sets are the hidden fraction of the
            // catalogue, not the catalogue.
            ops.opsForSet().members(RecoKeys.VIDEO_PRIVATE);
            ops.opsForSet().members(RecoKeys.VIDEO_TAKEN_DOWN);
            ops.opsForSet().members(RecoKeys.VIDEO_HELD);
        });
        Map<String, Double> trending = trendingCandidates(RedisPipeline.answer(reads, 0));
        Map<String, Double> topTags = topTags(RedisPipeline.answer(reads, 1));
        Map<String, Double> affinityByVideo = new HashMap<>();
        Map<String, Integer> tagOverlap = new HashMap<>();
        Map<String, List<String>> reasons = new HashMap<>();

        Map<String, Set<String>> tagged = videosTagged(topTags.keySet());
        for (Map.Entry<String, Double> tag : topTags.entrySet()) {
            for (String videoId : tagged.get(tag.getKey())) {
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
        // Last, so under the cap it is the exploration draw that gets cut, never a tag match or
        // a trending video.
        Set<String> hidden = new HashSet<>();
        for (int i = 5; i <= 7; i++) {
            Set<String> members = RedisPipeline.answer(reads, i);
            if (members != null) {
                hidden.addAll(members);
            }
        }
        for (String videoId : exploration(RedisPipeline.answer(reads, 4), pool, hidden)) {
            pool.add(videoId);
            reasons.computeIfAbsent(videoId, id -> new ArrayList<>()).add("explore");
        }

        Set<String> excluded = alreadyDelivered(RedisPipeline.answer(reads, 2), RedisPipeline.answer(reads, 3));
        excluded.addAll(byMutedAccounts(userId, pool));
        List<String> candidates = pool.stream()
                .filter(videoId -> !excluded.contains(videoId))
                .limit(CANDIDATE_POOL)
                .toList();

        if (candidates.isEmpty()) {
            return List.of();
        }

        // The three per-candidate lookups in one round trip, each one ZMSCORE for the whole set.
        Object[] members = candidates.toArray();
        List<Object> scored = RedisPipeline.run(redisTemplate, ops -> {
            ops.opsForZSet().score(RecoKeys.VIDEO_WATCHES, members);
            ops.opsForZSet().score(RecoKeys.VIDEO_COMPLETIONS, members);
            ops.opsForZSet().score(RecoKeys.VIDEO_PUBLISHED, members);
        });
        Counters counters = counters(candidates, RedisPipeline.answer(scored, 0), RedisPipeline.answer(scored, 1));
        Map<String, Double> ages = ageHours(candidates, RedisPipeline.answer(scored, 2));

        Map<String, Double> modelScores = rankClient.score(userId,
                features(candidates, counters, ages, affinityByVideo, tagOverlap));

        Map<String, Double> scores = modelScores.isEmpty()
                ? heuristicScores(candidates, trending, affinityByVideo, counters)
                : modelScores;
        if (!modelScores.isEmpty()) {
            candidates.forEach(videoId -> reasons.computeIfAbsent(videoId, id -> new ArrayList<>()).add("model"));
        }

        Map<String, Double> order = perturbed(candidates, scores);
        List<FeedItemResponse> feed = candidates.stream()
                .sorted(Comparator.comparingDouble(order::get).reversed())
                .limit(limit)
                .map(videoId -> new FeedItemResponse(
                        videoId,
                        round(scores.getOrDefault(videoId, 0.0)),
                        reasons.getOrDefault(videoId, List.of())))
                .toList();

        markServed(userId, feed);
        return feed;
    }

    /** The catalogue draw, minus what trending and the tags already reached and minus the hidden. */
    private List<String> exploration(List<String> drawn, Set<String> alreadyInPool, Set<String> hidden) {
        if (drawn == null) {
            return List.of();
        }
        return drawn.stream()
                .filter(videoId -> videoId != null && !alreadyInPool.contains(videoId) && !hidden.contains(videoId))
                .distinct()
                .toList();
    }

    /**
     * Score plus Gumbel noise scaled to the candidates' spread, for the sort only — the response
     * still carries the real score. All-equal scores have no spread and get no noise.
     */
    private Map<String, Double> perturbed(List<String> candidates, Map<String, Double> scores) {
        double min = candidates.stream().mapToDouble(id -> scores.getOrDefault(id, 0.0)).min().orElse(0.0);
        double max = candidates.stream().mapToDouble(id -> scores.getOrDefault(id, 0.0)).max().orElse(0.0);
        double temperature = EXPLORATION_TEMPERATURE * (max - min);
        Map<String, Double> order = new HashMap<>();
        for (String videoId : candidates) {
            double gumbel = -Math.log(-Math.log(random.nextDouble()));
            order.put(videoId, scores.getOrDefault(videoId, 0.0) + temperature * gumbel);
        }
        return order;
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

    private Map<String, Double> trendingCandidates(Set<ZSetOperations.TypedTuple<String>> tuples) {
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
    private Map<String, Double> topTags(Set<ZSetOperations.TypedTuple<String>> tuples) {
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

    /** The newest videos under each tag, every tag in the same round trip. */
    private Map<String, Set<String>> videosTagged(Collection<String> tags) {
        List<String> ordered = List.copyOf(tags);
        List<Object> results = ordered.isEmpty() ? List.of() : RedisPipeline.run(redisTemplate, ops ->
                ordered.forEach(tag -> ops.opsForZSet().reverseRange(RecoKeys.tagIndex(tag), 0, PER_TAG - 1)));
        Map<String, Set<String>> byTag = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            Set<String> videoIds = RedisPipeline.answer(results, i);
            byTag.put(ordered.get(i), videoIds == null ? Set.of() : videoIds);
        }
        return byTag;
    }

    /**
     * Everything this viewer must not be handed again: what they watched, plus what they were
     * offered in the last half hour. Reading both is what lets the client page through the feed
     * by simply asking again — see {@link RecoKeys#userServed} for why a cursor does not work
     * here and why the two sets are not one.
     */
    private Set<String> alreadyDelivered(Set<String> seen, Set<String> served) {
        Set<String> excluded = new HashSet<>();
        if (seen != null) {
            excluded.addAll(seen);
        }
        if (served != null) {
            excluded.addAll(served);
        }
        return excluded;
    }

    /**
     * Videos in the pool posted by an account the viewer muted. Filtered before the pool cap, so
     * a muted account cannot use up the viewer's five hundred slots either. A video published
     * before owners were recorded has no entry and is let through.
     */
    private Set<String> byMutedAccounts(Long userId, Set<String> pool) {
        Set<Long> muted = userMuteClient.mutedIds(userId);
        if (muted.isEmpty() || pool.isEmpty()) {
            return Set.of();
        }
        List<Object> videoIds = List.copyOf(pool);
        List<Object> owners = redisTemplate.opsForHash().multiGet(RecoKeys.VIDEO_OWNER, videoIds);
        Set<String> hidden = new HashSet<>();
        for (int i = 0; i < videoIds.size(); i++) {
            Object owner = owners == null ? null : owners.get(i);
            if (owner != null && muted.contains(Long.valueOf(owner.toString()))) {
                hidden.add((String) videoIds.get(i));
            }
        }
        return hidden;
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
        RedisPipeline.run(redisTemplate, ops -> {
            ops.opsForZSet().add(key, served);
            ops.opsForZSet().removeRange(key, 0, -RecoKeys.TRIM_TO - 1);
            // Refreshed on every request on purpose: the window is meant to cover a scrolling
            // session, and a session that is still going should not have its head expire under it.
            ops.expire(key, RecoKeys.SERVED_TTL);
        });
    }

    /** Raw watch counts alongside the completion rate, because the model wants both. */
    private record Counters(Map<String, Double> watches, Map<String, Double> completionRates) {
    }

    /**
     * From two ZMSCORE answers for the whole candidate set rather than a round trip per video: at
     * 500 candidates the difference is a feed that answers in one hop and one that answers in a
     * thousand.
     */
    private Counters counters(List<String> videoIds, List<Double> watches, List<Double> completions) {
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

    private Map<String, Double> ageHours(List<String> videoIds, List<Double> publishedAt) {
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
