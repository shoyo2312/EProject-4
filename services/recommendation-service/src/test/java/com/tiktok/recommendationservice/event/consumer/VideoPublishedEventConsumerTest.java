package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoPublishedEventConsumerTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private InboxService inboxService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void onMessage_newEvent_recordsPublish() throws Exception {
        VideoPublishedEventConsumer consumer = new VideoPublishedEventConsumer(recommendationService, inboxService, objectMapper);
        VideoPublishedEvent event = VideoPublishedEvent.of("vid1", 1L, "My video", "s3://raw/vid1.mp4");
        when(inboxService.markIfNew(event.eventId())).thenReturn(true);

        consumer.onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService).recordVideoPublished("vid1");
    }

    @Test
    void onMessage_duplicateEvent_isSkipped() throws Exception {
        VideoPublishedEventConsumer consumer = new VideoPublishedEventConsumer(recommendationService, inboxService, objectMapper);
        VideoPublishedEvent event = VideoPublishedEvent.of("vid1", 1L, "My video", "s3://raw/vid1.mp4");
        when(inboxService.markIfNew(event.eventId())).thenReturn(false);

        consumer.onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService, never()).recordVideoPublished("vid1");
    }
}
