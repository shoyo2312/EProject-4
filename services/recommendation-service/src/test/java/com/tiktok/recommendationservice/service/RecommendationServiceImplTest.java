package com.tiktok.recommendationservice.service;

import com.tiktok.recommendationservice.dto.response.TrendingVideoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceImplTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ZSetOperations<String, String> zSetOperations;

    private RecommendationServiceImpl recommendationService;

    @BeforeEach
    void setUp() {
        recommendationService = new RecommendationServiceImpl(redisTemplate);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);
    }

    @Test
    void recordVideoPublished_incrementsByPublishWeight() {
        recommendationService.recordVideoPublished("vid1");

        verify(zSetOperations).incrementScore("reco:trending", "vid1", 1.0);
    }

    @Test
    void recordLike_liked_incrementsByLikeWeight() {
        recommendationService.recordLike("vid1", true);

        verify(zSetOperations).incrementScore("reco:trending", "vid1", 3.0);
    }

    @Test
    void recordLike_unliked_decrementsByLikeWeight() {
        recommendationService.recordLike("vid1", false);

        verify(zSetOperations).incrementScore("reco:trending", "vid1", -3.0);
    }

    @Test
    void recordShare_incrementsByShareWeight() {
        recommendationService.recordShare("vid1");

        verify(zSetOperations).incrementScore("reco:trending", "vid1", 5.0);
    }

    @Test
    void recordComment_incrementsByCommentWeight() {
        recommendationService.recordComment("vid1");

        verify(zSetOperations).incrementScore("reco:trending", "vid1", 2.0);
    }

    @Test
    void getTrending_mapsTuplesInRankOrder() {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>(List.of(
                ZSetOperations.TypedTuple.of("vid1", 10.0),
                ZSetOperations.TypedTuple.of("vid2", 5.0)
        ));
        when(zSetOperations.reverseRangeWithScores("reco:trending", 0, 1)).thenReturn(tuples);

        List<TrendingVideoResponse> result = recommendationService.getTrending(2);

        assertThat(result).containsExactly(
                new TrendingVideoResponse("vid1", 10.0),
                new TrendingVideoResponse("vid2", 5.0)
        );
    }

    @Test
    void getTrending_noData_returnsEmptyList() {
        when(zSetOperations.reverseRangeWithScores("reco:trending", 0, 19)).thenReturn(null);

        assertThat(recommendationService.getTrending(20)).isEmpty();
    }
}
