package com.tiktok.analyticsservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;

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

        consumer().onMessages(List.of(record(objectMapper.writeValueAsString(event), "CommentCreatedEvent")));

        verify(engagementEventRepository).insertAll(List.of(
                new EngagementEventRepository.Row(event.eventId(), "COMMENTED", "42", 7L, event.occurredAt())));
    }

    /**
     * The topic carries both types and their JSON has no discriminator, so a deletion parses as a
     * creation with content null. Without routing on the header every removed comment was counted
     * as one more comment.
     */
    @Test
    void deletedEvent_isRecordedAsARemovalNotAsANewComment() throws Exception {
        CommentDeletedEvent event = CommentDeletedEvent.of(1L, 42L, 7L);

        consumer().onMessages(List.of(record(objectMapper.writeValueAsString(event), "CommentDeletedEvent")));

        verify(engagementEventRepository).insertAll(List.of(
                new EngagementEventRepository.Row(event.eventId(), "UNCOMMENTED", "42", 7L, event.occurredAt())));
    }

    @Test
    void missingHeader_isReadAsACreationLikeTheOlderProducer() throws Exception {
        CommentCreatedEvent event = CommentCreatedEvent.of(2L, 42L, 7L, "hi", null, null);

        consumer().onMessages(List.of(record(objectMapper.writeValueAsString(event), null)));

        verify(engagementEventRepository).insertAll(List.of(
                new EngagementEventRepository.Row(event.eventId(), "COMMENTED", "42", 7L, event.occurredAt())));
    }

    /** Routed per record: one poll can mix both types, and each keeps its own. */
    @Test
    void aPollMixingBothTypesIsOneInsertWithEachRoutedByItsOwnHeader() throws Exception {
        CommentCreatedEvent created = CommentCreatedEvent.of(3L, 42L, 7L, "hi", null, null);
        CommentDeletedEvent deleted = CommentDeletedEvent.of(3L, 42L, 7L);

        consumer().onMessages(List.of(
                record(objectMapper.writeValueAsString(created), "CommentCreatedEvent"),
                record(objectMapper.writeValueAsString(deleted), "CommentDeletedEvent")));

        verify(engagementEventRepository).insertAll(List.of(
                new EngagementEventRepository.Row(created.eventId(), "COMMENTED", "42", 7L, created.occurredAt()),
                new EngagementEventRepository.Row(deleted.eventId(), "UNCOMMENTED", "42", 7L, deleted.occurredAt())));
    }

    private static ConsumerRecord<String, String> record(String payload, String eventType) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("interaction.comment-events", 0, 0L, "42", payload);
        if (eventType != null) {
            record.headers().add("eventType", eventType.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
