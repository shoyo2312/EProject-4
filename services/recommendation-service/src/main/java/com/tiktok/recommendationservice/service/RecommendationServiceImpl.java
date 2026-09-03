package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    private static final double PUBLISH_SCORE = 1.0;
    private static final double LIKE_SCORE = 3.0;
    private static final double SHARE_SCORE = 5.0;
    private static final double COMMENT_SCORE = 2.0;

    /**
     * A watch is worth up to this much, scaled by how much of the video was actually watched.
     * Deliberately below a like: watching is passive and cheap, liking is a decision.
     */
    private static final double WATCH_SCORE = 2.0;

    /**
     * How much of one hour's engagement survives into the next. 0.85 over a 24-hour window
     * leaves the oldest hour worth about 2% of the newest, which is the point — trending should
     * be about the last few hours, with the tail there only to break ties.
     */
    private static final double HOURLY_DECAY = 0.85;

    /**
     * Below this, a watch is a skip, and a skip is the most informative thing a viewer does:
     * it is the only negative signal in a feed where they never have to press anything.
     */
    private static final double SKIP_RATIO = 0.2;
    private static final double SKIP_PENALTY = -0.5;

    private final StringRedisTemplate redisTemplate;

    @Override
    public void recordVideoUploaded(String videoId, List<String> tags) {
        if (tags.isEmpty()) {
            return;
        }
        String tagKey = RecoKeys.videoTags(videoId);
        redisTemplate.opsForSet().add(tagKey, tags.toArray(String[]::new));
        redisTemplate.expire(tagKey, RecoKeys.PROFILE_TTL);

        // Two topics, so nothing orders the publication against the transcode result as far as
        // this service is concerned: a lagging video.video-events partition can hand us the
        // upload after the video is already live. Indexing the tags here in that case is what
        // keeps a ready video out of half the indexes it belongs in.
        Double publishedAt = redisTemplate.opsForZSet().score(RecoKeys.VIDEO_PUBLISHED, videoId);
        if (publishedAt != null) {
            indexTags(videoId, tags, publishedAt);
        }
    }

    @Override
    public void recordVideoReady(String videoId) {
        addEngagement(videoId, PUBLISH_SCORE);
        double publishedAt = Instant.now().getEpochSecond();
        // Recorded for every video, not only tagged ones: the ranking model asks how old a video
        // is whether or not this viewer has any interest in its tags.
        redisTemplate.opsForZSet().add(RecoKeys.VIDEO_PUBLISHED, videoId, publishedAt);

        Set<String> tags = redisTemplate.opsForSet().members(RecoKeys.videoTags(videoId));
        if (tags == null || tags.isEmpty()) {
            return;
        }
        indexTags(videoId, tags, publishedAt);
    }

    private void indexTags(String videoId, Iterable<String> tags, double publishedAt) {
        for (String tag : tags) {
            String index = RecoKeys.tagIndex(tag);
            redisTemplate.opsForZSet().add(index, videoId, publishedAt);
            // Trimmed by rank, which for this set is by publish time: a tag index only ever
            // needs to answer "what is new under this tag", so the old tail is dead weight.
            redisTemplate.opsForZSet().removeRange(index, 0, -RecoKeys.TRIM_TO - 1);
            redisTemplate.expire(index, RecoKeys.PROFILE_TTL);
        }
    }

    /**
     * The hourly buckets are cleared as well as the ranking they feed, and that is the part worth
     * getting right: {@link #rebuildTrending} unions the last 24 buckets back into TRENDING every
     * minute, so removing the video from TRENDING alone puts it back within the minute and keeps
     * doing so for a day.
     *
     * <p>The two per-user sets are deliberately left alone. They only ever exclude a video from
     * someone's feed, so a stale entry costs nothing, and reaching every viewer who was offered
     * this video would mean scanning a key per user.
     */
    @Override
    public void recordVideoDeleted(String videoId) {
        Set<String> tags = redisTemplate.opsForSet().members(RecoKeys.videoTags(videoId));
        if (tags != null) {
            for (String tag : tags) {
                redisTemplate.opsForZSet().remove(RecoKeys.tagIndex(tag), videoId);
            }
        }
        redisTemplate.delete(RecoKeys.videoTags(videoId));

        RecoKeys.trendingWindow(Instant.now())
                .forEach(bucket -> redisTemplate.opsForZSet().remove(bucket, videoId));

        redisTemplate.opsForZSet().remove(RecoKeys.TRENDING, videoId);
        redisTemplate.opsForZSet().remove(RecoKeys.VIDEO_PUBLISHED, videoId);
        redisTemplate.opsForZSet().remove(RecoKeys.VIDEO_WATCHES, videoId);
        redisTemplate.opsForZSet().remove(RecoKeys.VIDEO_COMPLETIONS, videoId);
    }

    @Override
    public void recordLike(String videoId, boolean liked) {
        addEngagement(videoId, liked ? LIKE_SCORE : -LIKE_SCORE);
    }

    @Override
    public void recordShare(String videoId) {
        addEngagement(videoId, SHARE_SCORE);
    }

    @Override
    public void recordComment(String videoId, boolean created) {
        addEngagement(videoId, created ? COMMENT_SCORE : -COMMENT_SCORE);
    }

    @Override
    public void recordWatch(String videoId, Long userId, long watchedMs, long durationMs, boolean completed) {
        double ratio = durationMs <= 0 ? 0 : Math.min(1.0, (double) watchedMs / durationMs);

        addEngagement(videoId, WATCH_SCORE * ratio);

        redisTemplate.opsForZSet().incrementScore(RecoKeys.VIDEO_WATCHES, videoId, 1);
        if (completed) {
            redisTemplate.opsForZSet().incrementScore(RecoKeys.VIDEO_COMPLETIONS, videoId, 1);
        }

        markSeen(userId, videoId);
        learnTags(userId, videoId, ratio);
    }

    /**
     * Rebuilds the decayed ranking from the hourly buckets. Runs on every replica; that is
     * harmless because a union of the same buckets with the same weights produces the same set
     * no matter how many times it runs, so this needs no leader election.
     */
    @Scheduled(fixedDelayString = "${reco.trending-rebuild-millis:60000}")
    public void rebuildTrending() {
        Instant now = Instant.now();
        List<String> buckets = RecoKeys.trendingWindow(now).toList();
        double[] weights = java.util.stream.IntStream.range(0, buckets.size())
                .mapToDouble(hoursAgo -> Math.pow(HOURLY_DECAY, hoursAgo))
                .toArray();

        try {
            redisTemplate.opsForZSet().unionAndStore(
                    buckets.get(0),
                    buckets.subList(1, buckets.size()),
                    RecoKeys.TRENDING,
                    org.springframework.data.redis.connection.zset.Aggregate.SUM,
                    org.springframework.data.redis.connection.zset.Weights.of(weights));

            // The two counters have no natural expiry — every video ever watched stays in them.
            // Trimming lowest-first keeps the most-watched, which are the only ones a feed can
            // realistically surface anyway.
            redisTemplate.opsForZSet().removeRange(RecoKeys.VIDEO_WATCHES, 0, -RecoKeys.QUALITY_TRIM_TO - 1);
            redisTemplate.opsForZSet().removeRange(RecoKeys.VIDEO_COMPLETIONS, 0, -RecoKeys.QUALITY_TRIM_TO - 1);
            // Trimmed lowest-score-first, which here means oldest-published-first.
            redisTemplate.opsForZSet().removeRange(RecoKeys.VIDEO_PUBLISHED, 0, -RecoKeys.QUALITY_TRIM_TO - 1);
        } catch (RuntimeException e) {
            // A failed rebuild leaves the previous ranking in place, which is stale but serving.
            log.warn("Trending rebuild failed, serving the previous ranking: {}", e.getMessage());
        }
    }

    @Override
    public List<TrendingVideoResponse> getTrending(int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(RecoKeys.TRENDING, 0, limit - 1);

        if (tuples == null) {
            return List.of();
        }

        return tuples.stream()
                .map(tuple -> new TrendingVideoResponse(tuple.getValue(), tuple.getScore()))
                .toList();
    }

    private void addEngagement(String videoId, double score) {
        String bucket = RecoKeys.trendBucket(Instant.now());
        redisTemplate.opsForZSet().incrementScore(bucket, videoId, score);
        redisTemplate.expire(bucket, RecoKeys.BUCKET_TTL);
    }

    private void markSeen(Long userId, String videoId) {
        String key = RecoKeys.userSeen(userId);
        redisTemplate.opsForZSet().add(key, videoId, Instant.now().getEpochSecond());
        redisTemplate.opsForZSet().removeRange(key, 0, -RecoKeys.TRIM_TO - 1);
        redisTemplate.expire(key, RecoKeys.PROFILE_TTL);
    }

    /**
     * Affinity moves by the watched fraction rather than by a flat point per view, because the
     * two are not the same evidence: sitting through a whole video under a tag says something
     * that scrolling past three of them does not.
     */
    private void learnTags(Long userId, String videoId, double ratio) {
        Set<String> tags = redisTemplate.opsForSet().members(RecoKeys.videoTags(videoId));
        if (tags == null || tags.isEmpty()) {
            return;
        }

        double delta = ratio < SKIP_RATIO ? SKIP_PENALTY : ratio;
        String key = RecoKeys.userTags(userId);
        for (String tag : tags) {
            redisTemplate.opsForZSet().incrementScore(key, tag, delta);
        }
        redisTemplate.expire(key, RecoKeys.PROFILE_TTL);
    }
}
