package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.user.UserFollowChangedEvent;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private FollowEventConsumer consumer() {
        return new FollowEventConsumer(
                new IdempotentEventProcessor(processedEventRepository), notificationService, objectMapper);
    }

    @Test
    void onMessage_notifiesTheAccountBeingFollowed() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(UserFollowChangedEvent.of(9L, 50L, true)));

        verify(notificationService).create(
                eq(50L), eq(NotificationType.NEW_FOLLOWER), any(), any(), eq("9"));
    }

    @Test
    void onMessage_ignoresAnUnfollow() throws Exception {
        consumer().onMessage(objectMapper.writeValueAsString(UserFollowChangedEvent.of(9L, 50L, false)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any());
    }
}
