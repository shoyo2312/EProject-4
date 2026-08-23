package com.tiktok.recommendationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.recommendationservice.service.InboxService;
import com.tiktok.recommendationservice.service.RecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommentEventConsumerTest {

    @Mock
    private RecommendationService recommendationService;

    @Mock
    private InboxService inboxService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private CommentEventConsumer consumer() {
        return new CommentEventConsumer(recommendationService, inboxService, objectMapper);
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
    void onMessage_creation_addsEngagement() throws Exception {
        CommentCreatedEvent event = CommentCreatedEvent.of(9L, 7L, 1L, "nice");
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event), header("CommentCreatedEvent"));

        verify(recommendationService).recordComment("7", true);
    }

    /**
     * The bug this routing exists for: a deletion differs from a creation only by the absent
     * {@code content}, so it parses cleanly as a creation and used to push the video *up* the
     * trending ranking every time somebody removed a comment.
     */
    @Test
    void onMessage_deletion_takesTheEngagementBack() throws Exception {
        CommentDeletedEvent event = CommentDeletedEvent.of(9L, 7L, 1L);
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event), header("CommentDeletedEvent"));

        verify(recommendationService).recordComment("7", false);
        verify(recommendationService, never()).recordComment("7", true);
    }

    /** A producer predating the deletion event sends no header, and only ever sent creations. */
    @Test
    void onMessage_withoutAHeader_isTreatedAsACreation() throws Exception {
        CommentCreatedEvent event = CommentCreatedEvent.of(9L, 7L, 1L, "nice");
        givenFirstDelivery(event.eventId());

        consumer().onMessage(objectMapper.writeValueAsString(event), null);

        verify(recommendationService).recordComment("7", true);
    }

    @Test
    void onMessage_duplicateEvent_isSkipped() throws Exception {
        CommentCreatedEvent event = CommentCreatedEvent.of(9L, 7L, 1L, "nice");

        consumer().onMessage(objectMapper.writeValueAsString(event), header("CommentCreatedEvent"));

        verify(recommendationService, never()).recordComment("7", true);
    }
}
