package com.tiktok.analyticsservice.event.consumer;

import com.tiktok.analyticsservice.repository.EngagementEventRepository;
import com.tiktok.event.interaction.VideoSavedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class VideoSavedEventConsumerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private EngagementEventRepository engagementEventRepository;

    @Test
    void aSaveIsSavedAndAnUnsaveIsUnsaved() throws Exception {
        VideoSavedEvent saved = VideoSavedEvent.of(42L, 7L, true);
        VideoSavedEvent unsaved = VideoSavedEvent.of(42L, 7L, false);

        new VideoSavedEventConsumer(engagementEventRepository, objectMapper).onMessages(List.of(
                new ConsumerRecord<>("interaction.save-events", 0, 0L, "42", objectMapper.writeValueAsString(saved)),
                new ConsumerRecord<>("interaction.save-events", 0, 1L, "42", objectMapper.writeValueAsString(unsaved))));

        verify(engagementEventRepository).insertAll(List.of(
                new EngagementEventRepository.Row(saved.eventId(), "SAVED", "42", 7L, saved.occurredAt()),
                new EngagementEventRepository.Row(unsaved.eventId(), "UNSAVED", "42", 7L, unsaved.occurredAt())));
    }
}
