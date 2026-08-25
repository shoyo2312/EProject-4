package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.entity.VideoCounters;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The point of these: a Redis outage must cost latency, not correctness. invalidate() runs inside
 * the compensated block of every like, share and comment, so anything it throws undoes a write
 * that had already succeeded.
 */
class CounterCacheServiceImplTest {

    private final VideoCountersRepository repository = mock(VideoCountersRepository.class);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private final CounterCacheServiceImpl service = new CounterCacheServiceImpl(repository, redisTemplate);

    private static final QueryTimeoutException REDIS_DOWN = new QueryTimeoutException("redis is not answering");

    @Test
    void getCounts_whenRedisIsDown_stillAnswersFromCassandra() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenThrow(REDIS_DOWN);
        doThrow(REDIS_DOWN).when(valueOps).set(anyString(), anyString(), any(Duration.class));
        when(repository.findById(7L)).thenReturn(Optional.of(
                VideoCounters.builder().videoId(7L).likeCount(3L).commentCount(2L).shareCount(1L).viewCount(9L).build()));

        VideoCounts counts = service.getCounts(7L);

        assertThat(counts.likeCount()).isEqualTo(3L);
        assertThat(counts.viewCount()).isEqualTo(9L);
    }

    @Test
    void invalidate_whenRedisIsDown_doesNotThrow() {
        when(redisTemplate.delete(anyString())).thenThrow(REDIS_DOWN);

        assertThatCode(() -> service.invalidate(7L)).doesNotThrowAnyException();
    }

    @Test
    void getCounts_withAValueInAnOlderShape_reloadsRatherThanParsingIt() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3:2:1");
        when(repository.findById(7L)).thenReturn(Optional.empty());

        assertThat(service.getCounts(7L)).isEqualTo(VideoCounts.ZERO);
        // Rewritten in the current shape, so the next read is a hit again.
        org.mockito.Mockito.verify(valueOps).set(eq("interaction:counters:7"), eq("0:0:0:0"), any(Duration.class));
    }

    @Test
    void getCounts_readsBackWhatItWrote() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn("3:2:1:9");

        VideoCounts counts = service.getCounts(7L);

        assertThat(counts).isEqualTo(new VideoCounts(3L, 2L, 1L, 9L));
        org.mockito.Mockito.verifyNoInteractions(repository);
    }
}
