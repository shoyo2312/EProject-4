package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.video.VideoTranscodedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VideoTranscodedEventConsumerTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private InboxService inboxService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private VideoTranscodedEventConsumer consumer() {
        return new VideoTranscodedEventConsumer(recommendationService, inboxService, objectMapper);
    }

    private void givenFirstDelivery(String eventId) {
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(inboxService).runOnce(eq(eventId), any());
    }

    @Test
    void onMessage_success_makesTheVideoReachableFromTheFeed() throws Exception {
        VideoTranscodedEvent event = VideoTranscodedEvent.success("vid1", "thumb", "preview", "hls", 12);
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService).recordVideoReady("vid1");
    }

    /**
     * A permanent transcode failure is the end of the video, and nothing else is coming: left
     * indexed it would keep being handed out until its owner happened to delete it.
     */
    @Test
    void onMessage_failure_dropsTheVideoInsteadOfIndexingIt() throws Exception {
        VideoTranscodedEvent event = VideoTranscodedEvent.failure("vid2", "ffmpeg exit 1");
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService).recordVideoDeleted("vid2");
        verify(recommendationService, never()).recordVideoReady(anyString());
    }

    @Test
    void onMessage_duplicateEvent_isSkipped() throws Exception {
        VideoTranscodedEvent event = VideoTranscodedEvent.success("vid3", "thumb", "preview", "hls", 12);
        // A mock InboxService runs nothing unless told to, which is the redelivery case itself.

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService, never()).recordVideoReady(anyString());
    }
}
