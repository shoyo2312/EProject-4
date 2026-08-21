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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

    private ViewService viewService() {
        return new ViewServiceImpl(
                viewByVideoRepository, videoCountersRepository, counterCacheService, eventPublisher);
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
}
