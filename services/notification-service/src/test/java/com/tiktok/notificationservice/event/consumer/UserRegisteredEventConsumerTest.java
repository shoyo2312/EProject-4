package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.user.UserRegisteredEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import com.tiktok.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserRegisteredEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void onMessage_newEvent_createsWelcomeNotificationAndMarksProcessed() throws Exception {
        UserRegisteredEventConsumer consumer = new UserRegisteredEventConsumer(notificationService, processedEventRepository, objectMapper);
        UserRegisteredEvent event = UserRegisteredEvent.of(1L, "alice", "alice@example.com");
        when(processedEventRepository.existsByEventId(event.eventId())).thenReturn(false);

        consumer.onMessage(objectMapper.writeValueAsString(event));

        ArgumentCaptor<String> titleCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(1L), eq(NotificationType.SYSTEM), titleCaptor.capture(), bodyCaptor.capture(), isNull());
        assertThat(bodyCaptor.getValue()).contains("alice");

        verify(processedEventRepository).save(argThat(saved -> saved.getEventId().equals(event.eventId())));
    }

    @Test
    void onMessage_duplicateEvent_isSkipped() throws Exception {
        UserRegisteredEventConsumer consumer = new UserRegisteredEventConsumer(notificationService, processedEventRepository, objectMapper);
        UserRegisteredEvent event = UserRegisteredEvent.of(1L, "alice", "alice@example.com");
        when(processedEventRepository.existsByEventId(event.eventId())).thenReturn(true);

        consumer.onMessage(objectMapper.writeValueAsString(event));

        verify(notificationService, never()).create(any(), any(), any(), any(), any());
        verify(processedEventRepository, never()).save(any());
    }
}
