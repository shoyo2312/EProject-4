package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Trending is a single global Redis sorted set keyed by video id, scored by weighted
 * engagement. There is no per-user "for you" personalization yet — every consumer here
 * feeds the same ranking, which is a reasonable v1 given no user-affinity data exists.
 */
@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    private static final String TRENDING_KEY = "reco:trending";

    private static final double PUBLISH_SCORE = 1.0;
    private static final double LIKE_SCORE = 3.0;
    private static final double SHARE_SCORE = 5.0;
    private static final double COMMENT_SCORE = 2.0;

    private final StringRedisTemplate redisTemplate;

    @Override
    public void recordVideoPublished(String videoId) {
        redisTemplate.opsForZSet().incrementScore(TRENDING_KEY, videoId, PUBLISH_SCORE);
    }

    @Override
    public void recordLike(String videoId, boolean liked) {
        redisTemplate.opsForZSet().incrementScore(TRENDING_KEY, videoId, liked ? LIKE_SCORE : -LIKE_SCORE);
    }

    @Override
    public void recordShare(String videoId) {
        redisTemplate.opsForZSet().incrementScore(TRENDING_KEY, videoId, SHARE_SCORE);
    }

    @Override
    public void recordComment(String videoId) {
        redisTemplate.opsForZSet().incrementScore(TRENDING_KEY, videoId, COMMENT_SCORE);
    }

    @Override
    public List<TrendingVideoResponse> getTrending(int limit) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                redisTemplate.opsForZSet().reverseRangeWithScores(TRENDING_KEY, 0, limit - 1);

        if (tuples == null) {
            return List.of();
        }

        return tuples.stream()
                .map(tuple -> new TrendingVideoResponse(tuple.getValue(), tuple.getScore()))
                .collect(Collectors.toList());
    }
}
