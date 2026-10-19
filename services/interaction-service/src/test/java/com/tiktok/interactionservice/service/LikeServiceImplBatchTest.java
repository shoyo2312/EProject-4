package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.response.LikeStatusResponse;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.repository.LikeByUserRepository;
import com.tiktok.interactionservice.repository.LikeByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Plain Mockito, no Cassandra: what matters here is which reads the batch issues, not what they
 * return, and the container the rest of this service's tests need would only buy boot time.
 * LikeServiceImplTest runs the same batch against a real Cassandra.
 */
@ExtendWith(MockitoExtension.class)
class LikeServiceImplBatchTest {

    @Mock
    private LikeByVideoRepository likeByVideoRepository;

    @Mock
    private LikeByUserRepository likeByUserRepository;

    @Mock
    private VideoCountersRepository videoCountersRepository;

    @Mock
    private CounterCacheService counterCacheService;

    @Mock
    private InteractionEventPublisher eventPublisher;

    @Mock
    private InteractionRateLimiter rateLimiter;

    private LikeService likeService() {
        lenient().when(counterCacheService.getCounts(anyLong())).thenReturn(VideoCounts.ZERO);
        return new LikeServiceImpl(likeByVideoRepository, likeByUserRepository,
                videoCountersRepository, counterCacheService, eventPublisher, rateLimiter,
                org.mockito.Mockito.mock(com.tiktok.interactionservice.client.VideoOwnershipClient.class));
    }

    /**
     * The ids arrive in a query string, so without a cap one request names thousands of
     * partitions. The whole page is one query, not one point read per id.
     */
    @Test
    void getStatuses_pastTheCap_readsOnlyOnePagesWorth() {
        List<Long> ids = LongStream.rangeClosed(1, 500).boxed().toList();

        List<LikeStatusResponse> statuses = likeService().getStatuses(ids, 1L);

        assertThat(statuses).hasSize(50);
        verify(likeByVideoRepository).findLikedAmong(LongStream.rangeClosed(1, 50).boxed().toList(), 1L);
        verify(likeByVideoRepository, never()).existsById(any());
    }

    /** Otherwise a list padded with one repeated id spends the whole cap on that one video. */
    @Test
    void getStatuses_repeatedIds_areCollapsedBeforeTheCap() {
        List<Long> ids = List.of(7L, 7L, 7L, 8L);

        List<LikeStatusResponse> statuses = likeService().getStatuses(ids, 1L);

        assertThat(statuses).extracting(LikeStatusResponse::videoId).containsExactly(7L, 8L);
        verify(likeByVideoRepository).findLikedAmong(List.of(7L, 8L), 1L);
    }
}
