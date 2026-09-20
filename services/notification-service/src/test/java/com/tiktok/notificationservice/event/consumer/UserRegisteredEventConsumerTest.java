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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserRegisteredEventConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private UserRegisteredEventConsumer consumer() {
        return new UserRegisteredEventConsumer(
                new IdempotentEventProcessor(processedEventRepository), notificationService, objectMapper);
    }

    @Test
    void onMessage_newEvent_createsWelcomeNotification() throws Exception {
        UserRegisteredEvent event = UserRegisteredEvent.of(1L, "alice", "alice@example.com");
        when(processedEventRepository.tryClaim(eq(event.eventId()), anyString())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(event));

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(notificationService).create(eq(1L), eq(NotificationType.SYSTEM), any(), bodyCaptor.capture(), isNull());
        assertThat(bodyCaptor.getValue()).contains("alice");
    }

    @Test
    void onMessage_duplicateEvent_isSkipped() throws Exception {
        UserRegisteredEvent event = UserRegisteredEvent.of(1L, "alice", "alice@example.com");
        when(processedEventRepository.tryClaim(eq(event.eventId()), anyString())).thenReturn(false);

        consumer().onMessage(objectMapper.writeValueAsString(event));

        verify(notificationService, never()).create(any(), any(), any(), any(), any());
    }

    @Test
    void onMessage_failureReleasesTheClaimSoTheEventCanBeRedelivered() throws Exception {
        UserRegisteredEvent event = UserRegisteredEvent.of(1L, "alice", "alice@example.com");
        when(processedEventRepository.tryClaim(eq(event.eventId()), anyString())).thenReturn(true);
        when(notificationService.create(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("mongo down"));

        String payload = objectMapper.writeValueAsString(event);
        try {
            consumer().onMessage(payload);
        } catch (IllegalStateException expected) {
            // rethrown so kafka-lib's error handler retries, then routes to the DLT
        }

        verify(processedEventRepository).releaseClaim(event.eventId());
    }
}
