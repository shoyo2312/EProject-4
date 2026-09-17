package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoModerationCompletedEvent;
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
class VideoModerationEventConsumerTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private InboxService inboxService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private VideoModerationEventConsumer consumer() {
        return new VideoModerationEventConsumer(recommendationService, inboxService, objectMapper);
    }

    private void givenFirstDelivery(String eventId) {
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(inboxService).runOnce(eq(eventId), any());
    }

    private VideoModerationCompletedEvent verdict(String videoId, ModerationVerdict verdict) {
        return VideoModerationCompletedEvent.of(videoId, verdict, "nsfw", 0.42, 1, 10, 900L,
                "model", "v1", null);
    }

    @Test
    void onMessage_approved_putsTheVideoInTheFeed() throws Exception {
        VideoModerationCompletedEvent event = verdict("vid1", ModerationVerdict.APPROVED);
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService).recordVideoReady("vid1");
    }

    /**
     * The whole point: a rejected video must not be a candidate. It is also the only event that
     * says so — no deletion follows an automatic rejection.
     */
    @Test
    void onMessage_rejected_keepsTheVideoOut() throws Exception {
        VideoModerationCompletedEvent event = verdict("vid2", ModerationVerdict.REJECTED);
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService).recordVideoDeleted("vid2");
        verify(recommendationService, never()).recordVideoReady(anyString());
    }

    /** An unfinished check is not a pass — REVIEW waits for a human, out of the feed. */
    @Test
    void onMessage_review_keepsTheVideoOut() throws Exception {
        VideoModerationCompletedEvent event =
                VideoModerationCompletedEvent.unavailable("vid3", "classifier unreachable");
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService).recordVideoDeleted("vid3");
        verify(recommendationService, never()).recordVideoReady(anyString());
    }

    @Test
    void onMessage_duplicateEvent_isSkipped() throws Exception {
        VideoModerationCompletedEvent event = verdict("vid4", ModerationVerdict.APPROVED);
        // A mock InboxService runs nothing unless told to, which is the redelivery case itself.

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(recommendationService, never()).recordVideoReady(anyString());
    }
}
