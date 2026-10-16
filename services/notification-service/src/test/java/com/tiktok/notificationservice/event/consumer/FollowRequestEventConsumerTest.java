package com.tiktok.notificationservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.user.FollowRequestedEvent;
import com.tiktok.notificationservice.client.BlockClient;
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
class FollowRequestEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private BlockClient blockClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private FollowRequestEventConsumer consumer() {
        return new FollowRequestEventConsumer(
                new IdempotentEventProcessor(processedEventRepository), notificationService, objectMapper, blockClient);
    }

    /** The deep link opens the requester's profile, where the owner decides. */
    @Test
    void onMessage_tellsTheOwnerWhoAsked() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(FollowRequestedEvent.of(9L, 50L)));

        verify(notificationService).create(
                eq(50L), eq(9L), eq(NotificationType.FOLLOW_REQUEST), any(), any(), eq("9"));
    }

    /** Blocking removes the request in user-service; it must not survive as an inbox entry. */
    @Test
    void onMessage_fromAnAccountTheOwnerHasSinceBlocked_tellsNobody() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);
        when(blockClient.isBlockedBetween(50L, 9L)).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(FollowRequestedEvent.of(9L, 50L)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }
}
