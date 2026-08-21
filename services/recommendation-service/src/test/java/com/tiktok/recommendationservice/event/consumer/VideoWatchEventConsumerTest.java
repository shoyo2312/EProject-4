package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.interaction.VideoWatchEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoWatchEventConsumerTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private InboxService inboxService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void onMessage_newEvent_foldsTheSessionIn() throws Exception {
        VideoWatchEvent event = VideoWatchEvent.of(42L, 7L, 9_000L, 10_000L, true);
        when(inboxService.markIfNew(event.eventId())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService).recordWatch("42", 7L, 9_000L, 10_000L, true);
    }

    /**
     * A rebalance replays the tail of a partition. Without the claim the same session would be
     * counted twice, which quietly inflates exactly the completion rate the ranking leans on.
     */
    @Test
    void onMessage_redeliveredEvent_isSkipped() throws Exception {
        VideoWatchEvent event = VideoWatchEvent.of(42L, 7L, 9_000L, 10_000L, true);
        when(inboxService.markIfNew(event.eventId())).thenReturn(false);

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService, never())
                .recordWatch(anyString(), anyLong(), anyLong(), anyLong(), anyBoolean());
    }

    private VideoWatchEventConsumer consumer() {
        return new VideoWatchEventConsumer(recommendationService, inboxService, objectMapper);
    }
}
