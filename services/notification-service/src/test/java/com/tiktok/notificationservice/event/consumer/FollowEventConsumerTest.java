package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.user.UserFollowChangedEvent;
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
class FollowEventConsumerTest {

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private BlockClient blockClient;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private FollowEventConsumer consumer() {
        return new FollowEventConsumer(
                new IdempotentEventProcessor(processedEventRepository), notificationService, objectMapper, blockClient);
    }

    @Test
    void onMessage_notifiesTheAccountBeingFollowed() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(UserFollowChangedEvent.of(9L, 50L, true)));

        verify(notificationService).create(
                eq(50L), eq(9L), eq(NotificationType.NEW_FOLLOWER), any(), any(), eq("9"));
    }

    @Test
    void onMessage_ignoresAnUnfollow() throws Exception {
        consumer().onMessage(objectMapper.writeValueAsString(UserFollowChangedEvent.of(9L, 50L, false)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    /** The owner approved it themselves, so the requester is the one told — and the owner is not. */
    @Test
    void onMessage_anApprovedRequest_tellsTheRequesterAndNotTheOwner() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(UserFollowChangedEvent.of(9L, 50L, true, true)));

        verify(notificationService).create(
                eq(9L), eq(50L), eq(NotificationType.FOLLOW_REQUEST_ACCEPTED), any(), any(), eq("50"));
        verify(notificationService, never()).create(
                any(), any(), eq(NotificationType.NEW_FOLLOWER), any(), any(), any());
    }

    /**
     * user-service refuses a follow across a block, but a block placed while the event was still
     * in flight is not something it can take back. The blocker is not told about the follow.
     */
    @Test
    void onMessage_betweenAccountsThatHaveSinceBlockedOneAnother_tellsNobody() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);
        when(blockClient.isBlockedBetween(50L, 9L)).thenReturn(true);
        when(blockClient.isBlockedBetween(9L, 50L)).thenReturn(true);

        consumer().onMessage(objectMapper.writeValueAsString(UserFollowChangedEvent.of(9L, 50L, true)));
        consumer().onMessage(objectMapper.writeValueAsString(UserFollowChangedEvent.of(9L, 50L, true, true)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    /** Events written before requestApproved existed carry no such key, and read as a plain follow. */
    @Test
    void onMessage_aPayloadFromBeforeFollowRequests_isAPlainFollow() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);

        consumer().onMessage("""
                {"eventId": "e-1", "occurredAt": "2026-09-01T10:00:00Z", "followerId": 9, "followingId": 50, "followed": true}
                """);

        verify(notificationService).create(
                eq(50L), eq(9L), eq(NotificationType.NEW_FOLLOWER), any(), any(), eq("9"));
    }
}
