package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.admin.UserBannedEvent;
import com.tiktok.event.admin.UserUnbannedEvent;
import com.tiktok.event.admin.UserWarnedEvent;
import com.tiktok.event.admin.VideoTakenDownEvent;
import com.tiktok.notificationservice.client.VideoOwnerClient;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import com.tiktok.notificationservice.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Everything on admin.moderation-events that concerns a person. Two things can go wrong quietly
 * here and neither fails anything: telling the wrong account (a video names its id, not its
 * owner), and telling nobody at all, which is what a missed eventType header would do.
 */
@ExtendWith(MockitoExtension.class)
class AdminModerationEventConsumerTest {

    private static final Long ADMIN_ID = 1L;
    private static final Long USER_ID = 50L;

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private VideoOwnerClient videoOwnerClient;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private AdminModerationEventConsumer consumer() {
        lenient().when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);
        return new AdminModerationEventConsumer(
                new IdempotentEventProcessor(processedEventRepository),
                notificationService, videoOwnerClient, objectMapper);
    }

    private byte[] header(String eventType) {
        return eventType.getBytes(StandardCharsets.UTF_8);
    }

    private String json(Object event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void aBanTellsTheAccountItHappenedTo() throws Exception {
        consumer().onMessage(json(UserBannedEvent.of(USER_ID, ADMIN_ID, "spam")), header("UserBannedEvent"));

        // actorId null: a SYSTEM notice is from the platform, and the admin's id is not the
        // recipient's business.
        verify(notificationService).create(
                eq(USER_ID), eq(null), eq(NotificationType.SYSTEM), any(), eq("spam"), eq(null));
    }

    @Test
    void aTakedownTellsTheVideosOwner() throws Exception {
        when(videoOwnerClient.ownerOf("v1")).thenReturn(USER_ID);

        consumer().onMessage(json(VideoTakenDownEvent.of("v1", ADMIN_ID, "nudity")),
                header("VideoTakenDownEvent"));

        verify(notificationService).create(
                eq(USER_ID), eq(null), eq(NotificationType.SYSTEM), any(), eq("nudity"), eq("v1"));
    }

    /** Same call the like path makes, same answer when it fails: drop it rather than wedge. */
    @Test
    void aTakedownWhoseOwnerCannotBeResolvedTellsNobody() throws Exception {
        when(videoOwnerClient.ownerOf("v1")).thenReturn(null);

        consumer().onMessage(json(VideoTakenDownEvent.of("v1", ADMIN_ID, "nudity")),
                header("VideoTakenDownEvent"));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aWarningOverAUserReportGoesToThatUser() throws Exception {
        consumer().onMessage(json(UserWarnedEvent.of("USER", String.valueOf(USER_ID), ADMIN_ID, "last warning")),
                header("UserWarnedEvent"));

        verify(notificationService).create(
                eq(USER_ID), eq(null), eq(NotificationType.SYSTEM), any(), eq("last warning"), eq(null));
    }

    @Test
    void aWarningOverAVideoReportGoesToTheVideosOwner() throws Exception {
        when(videoOwnerClient.ownerOf("v1")).thenReturn(USER_ID);

        consumer().onMessage(json(UserWarnedEvent.of("VIDEO", "v1", ADMIN_ID, "keep it clean")),
                header("UserWarnedEvent"));

        verify(notificationService).create(
                eq(USER_ID), eq(null), eq(NotificationType.SYSTEM), any(), eq("keep it clean"), eq("v1"));
    }

    /** No client can name a comment's author, so the warning stays in the audit log. */
    @Test
    void aWarningOverACommentReportTellsNobody() throws Exception {
        consumer().onMessage(json(UserWarnedEvent.of("COMMENT", "v1:9", ADMIN_ID, "be civil")),
                header("UserWarnedEvent"));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    /**
     * Good news is not announced, and the events of other services share this topic. Routing is
     * by header alone — UserBanned and UserUnbanned are the same JSON — so a payload arriving
     * under the wrong one, or none, must not notify anyone.
     */
    @Test
    void ignoresEverythingElseOnTheTopic() throws Exception {
        String ban = json(UserBannedEvent.of(USER_ID, ADMIN_ID, "spam"));

        consumer().onMessage(json(UserUnbannedEvent.of(USER_ID, ADMIN_ID, "appeal upheld")),
                header("UserUnbannedEvent"));
        consumer().onMessage(ban, header("CommentRemovedEvent"));
        consumer().onMessage(ban, null);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
        verify(videoOwnerClient, never()).ownerOf(anyString());
    }

    /** A redelivery is routine on this topic; the person must not be told twice. */
    @Test
    void doesNotNotifyTwiceForTheSameEvent() throws Exception {
        UserBannedEvent event = UserBannedEvent.of(USER_ID, ADMIN_ID, "spam");
        when(processedEventRepository.tryClaim(eq(event.eventId()), anyString()))
                .thenReturn(true).thenReturn(false);
        AdminModerationEventConsumer consumer = new AdminModerationEventConsumer(
                new IdempotentEventProcessor(processedEventRepository),
                notificationService, videoOwnerClient, objectMapper);

        consumer.onMessage(json(event), header("UserBannedEvent"));
        consumer.onMessage(json(event), header("UserBannedEvent"));

        verify(notificationService).create(anyLong(), any(), any(), any(), any(), any());
    }
}
