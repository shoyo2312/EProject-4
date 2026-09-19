package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CommentCreatedEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Mock
    private EngagementEventRepository engagementEventRepository;

    private CommentCreatedEventConsumer consumer() {
        return new CommentCreatedEventConsumer(engagementEventRepository, objectMapper);
    }

    @Test
    void createdEvent_isRecordedAsAComment() throws Exception {
        CommentCreatedEvent event = CommentCreatedEvent.of(1L, 42L, 7L, "hi", null, null);

        consumer().onMessage(objectMapper.writeValueAsString(event), header("CommentCreatedEvent"));

        verify(engagementEventRepository).insert(eq(event.eventId()), eq("COMMENTED"), eq("42"), eq(7L), any());
    }

    /**
     * The topic carries both types and their JSON has no discriminator, so a deletion parses as a
     * creation with content null. Without routing on the header every removed comment was counted
     * as one more comment.
     */
    @Test
    void deletedEvent_isRecordedAsARemovalNotAsANewComment() throws Exception {
        CommentDeletedEvent event = CommentDeletedEvent.of(1L, 42L, 7L);

        consumer().onMessage(objectMapper.writeValueAsString(event), header("CommentDeletedEvent"));

        verify(engagementEventRepository, never()).insert(anyString(), eq("COMMENTED"), anyString(), anyLong(), any());
        verify(engagementEventRepository).insert(eq(event.eventId()), eq("UNCOMMENTED"), eq("42"), eq(7L), any());
    }

    @Test
    void missingHeader_isReadAsACreationLikeTheOlderProducer() throws Exception {
        CommentCreatedEvent event = CommentCreatedEvent.of(2L, 42L, 7L, "hi", null, null);

        consumer().onMessage(objectMapper.writeValueAsString(event), null);

        verify(engagementEventRepository).insert(eq(event.eventId()), eq("COMMENTED"), eq("42"), eq(7L), any());
    }

    private static byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }
}
