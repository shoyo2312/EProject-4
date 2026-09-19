package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.VideoRestoredEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminModerationEventConsumerTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private InboxService inboxService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private AdminModerationEventConsumer consumer() {
        return new AdminModerationEventConsumer(recommendationService, inboxService, objectMapper);
    }

    private void givenFirstDelivery(String eventId) {
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(inboxService).runOnce(eq(eventId), any());
    }

    private byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void takedown_withdrawsTheVideo() throws Exception {
        VideoTakenDownEvent event = VideoTakenDownEvent.of("vid1", 1L, "spam");
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event), header("VideoTakenDownEvent"));

        verify(recommendationService).recordTakenDown("vid1");
    }

    @Test
    void restore_putsTheVideoBack() throws Exception {
        VideoRestoredEvent event = VideoRestoredEvent.of("vid1", 1L, "appeal");
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event), header("VideoRestoredEvent"));

        verify(recommendationService).recordRestored("vid1");
    }

    /** A shared topic: other services' events, and header-less ones, are not ours to guess at. */
    @Test
    void otherEvents_areIgnored() throws Exception {
        UserBannedEvent banned = UserBannedEvent.of(5L, 1L, "abuse");

        consumer().onMessage(objectMapper.writeValueAsString(banned), header("UserBannedEvent"));
        consumer().onMessage(objectMapper.writeValueAsString(VideoTakenDownEvent.of("vid1", 1L, "x")), null);

        verifyNoInteractions(recommendationService);
    }
}
