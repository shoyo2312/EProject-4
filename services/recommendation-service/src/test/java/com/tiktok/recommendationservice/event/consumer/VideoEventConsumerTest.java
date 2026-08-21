package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.video.VideoDeletedEvent;
import com.tiktok.event.video.VideoPublishedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoEventConsumerTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private InboxService inboxService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private VideoEventConsumer consumer() {
        return new VideoEventConsumer(recommendationService, inboxService, objectMapper);
    }

    private byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void onMessage_newEvent_recordsPublish() throws Exception {
        VideoPublishedEvent event = VideoPublishedEvent.of("vid1", 1L, "My video", "s3://raw/vid1.mp4", List.of("dance"));
        when(inboxService.markIfNew(event.eventId())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(event), header("VideoPublishedEvent"));

        verify(recommendationService).recordVideoPublished("vid1", List.of("dance"));
    }

    @Test
    void onMessage_duplicateEvent_isSkipped() throws Exception {
        VideoPublishedEvent event = VideoPublishedEvent.of("vid1", 1L, "My video", "s3://raw/vid1.mp4", List.of("dance"));
        when(inboxService.markIfNew(event.eventId())).thenReturn(false);

        consumer().onMessage(objectMapper.writeValueAsString(event), header("VideoPublishedEvent"));

        verify(recommendationService, never()).recordVideoPublished("vid1", List.of("dance"));
    }

    /**
     * A producer predating the deletion event sends no header at all, and everything it sent was
     * a publication. Treating that as unroutable would stop the feed learning about new videos.
     */
    @Test
    void onMessage_withoutAHeader_isTreatedAsAPublication() throws Exception {
        VideoPublishedEvent event = VideoPublishedEvent.of("vid2", 1L, "My video", "s3://raw/vid2.mp4", List.of());
        when(inboxService.markIfNew(event.eventId())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(event), null);

        verify(recommendationService).recordVideoPublished("vid2", List.of());
    }

    @Test
    void onMessage_deletion_removesTheVideoFromTheRanking() throws Exception {
        VideoDeletedEvent event = VideoDeletedEvent.of("vid3", 1L, "s3://raw/vid3.mp4");
        when(inboxService.markIfNew(event.eventId())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(event), header("VideoDeletedEvent"));

        verify(recommendationService).recordVideoDeleted("vid3");
    }

    /**
     * Routing on the header rather than on the payload shape. A deletion parses cleanly as a
     * publication with a null title and null tags, so without the header check the feed would
     * index the video it was just told to drop.
     */
    @Test
    void onMessage_deletion_isNotRecordedAsAPublication() throws Exception {
        VideoDeletedEvent event = VideoDeletedEvent.of("vid4", 1L, "s3://raw/vid4.mp4");
        when(inboxService.markIfNew(event.eventId())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(event), header("VideoDeletedEvent"));

        verify(recommendationService, never()).recordVideoPublished("vid4", null);
    }
}
