package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.user.FollowRequestedEvent;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import com.tiktok.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowRequestEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    /** The deep link opens the requester's profile, where the owner decides. */
    @Test
    void onMessage_tellsTheOwnerWhoAsked() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        new FollowRequestEventConsumer(
                new IdempotentEventProcessor(processedEventRepository), notificationService, objectMapper)
                .onMessage(objectMapper.writeValueAsString(FollowRequestedEvent.of(9L, 50L)));

        verify(notificationService).create(
                eq(50L), eq(9L), eq(NotificationType.FOLLOW_REQUEST), any(), any(), eq("9"));
    }
}
