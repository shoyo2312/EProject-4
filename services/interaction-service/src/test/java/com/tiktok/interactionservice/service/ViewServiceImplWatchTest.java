package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.request.WatchRequest;
import com.tiktok.interactionservice.dto.response.WatchResponse;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import com.tiktok.interactionservice.repository.ViewByVideoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.tiktok.interactionservice.exception.WatchRateLimitedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Plain Mockito, no Cassandra: recording a watch writes nothing — it exists to put a row on a
 * Kafka topic — so the container the rest of this service's tests need would only buy boot time.
 */
@ExtendWith(MockitoExtension.class)
class ViewServiceImplWatchTest {

    @Mock
    private ViewByVideoRepository viewByVideoRepository;

    @Mock
    private VideoCountersRepository videoCountersRepository;

    @Mock
    private CounterCacheService counterCacheService;

    @Mock
    private InteractionEventPublisher eventPublisher;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    /** Sessions reported so far in the current window, as the rate-limit counter would return it. */
    private ViewService viewServiceAtSession(long session) {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(session);
        return new ViewServiceImpl(viewByVideoRepository, videoCountersRepository,
                counterCacheService, eventPublisher, redisTemplate);
    }

    private ViewService viewService() {
        return viewServiceAtSession(1L);
    }

    @Test
    void recordWatch_publishesTheSessionAndTouchesNoCounter() {
        WatchResponse response = viewService().recordWatch(7L, 1L, new WatchRequest(9_000L, 10_000L));

        assertThat(response.completed()).isTrue();
        assertThat(response.watchedMs()).isEqualTo(9_000L);
        verify(eventPublisher).publishWatch(7L, 1L, 9_000L, 10_000L, true);
        verifyNoInteractions(viewByVideoRepository, videoCountersRepository, counterCacheService);
    }

    @Test
    void recordWatch_belowTheThreshold_isNotCompleted() {
        WatchResponse response = viewService().recordWatch(7L, 1L, new WatchRequest(5_000L, 10_000L));

        assertThat(response.completed()).isFalse();
        verify(eventPublisher).publishWatch(7L, 1L, 5_000L, 10_000L, false);
    }

    /**
     * watchedMs is a client-supplied number. Unclamped, a claim of an hour on a ten-second video
     * is not merely a wrong row — it is the highest completion ratio in the training set, which
     * is precisely the kind of row a ranker weights most heavily.
     */
    @Test
    void recordWatch_impossibleWatchTime_isClampedToTheVideoLength() {
        WatchResponse response = viewService().recordWatch(7L, 1L, new WatchRequest(3_600_000L, 10_000L));

        assertThat(response.watchedMs()).isEqualTo(10_000L);
        verify(eventPublisher).publishWatch(7L, 1L, 10_000L, 10_000L, true);
    }

    /**
     * The counter deduplicates a viewer to once a day; the label must not. A rewatch is the
     * strongest positive signal there is, and dropping it would train the model on first
     * impressions alone.
     */
    @Test
    void recordWatch_sameViewerAgain_isPublishedAgain() {
        ViewService viewService = viewService();

        viewService.recordWatch(7L, 1L, new WatchRequest(9_000L, 10_000L));
        viewService.recordWatch(7L, 1L, new WatchRequest(9_500L, 10_000L));

        verify(eventPublisher, times(2))
                .publishWatch(eq(7L), eq(1L), anyLong(), anyLong(), anyBoolean());
    }

    /**
     * durationMs is client-supplied as well, so clamping watchedMs against it is no constraint at
     * all: a caller that sends both as an hour reports a perfect completion on a video this
     * platform would never have played. The ceiling is what makes the pair mean anything.
     */
    @Test
    void recordWatch_impossibleDuration_isClampedToWhatThePlatformCanPlay() {
        long tenMinutes = 600_000L;

        WatchResponse response = viewService().recordWatch(7L, 1L, new WatchRequest(3_600_000L, 3_600_000L));

        assertThat(response.watchedMs()).isEqualTo(tenMinutes);
        verify(eventPublisher).publishWatch(7L, 1L, tenMinutes, tenMinutes, true);
    }

    /**
     * A watch event is a vote on where a video ranks and costs one HTTP request, so without a
     * ceiling on how many one viewer may cast for one video, the training set is whatever the
     * loudest script says it is.
     */
    @Test
    void recordWatch_pastTheSessionLimit_isRefusedAndNotPublished() {
        ViewService viewService = viewServiceAtSession(61L);

        assertThatThrownBy(() -> viewService.recordWatch(7L, 1L, new WatchRequest(9_000L, 10_000L)))
                .isInstanceOf(WatchRateLimitedException.class);

        verify(eventPublisher, never()).publishWatch(anyLong(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }
}
