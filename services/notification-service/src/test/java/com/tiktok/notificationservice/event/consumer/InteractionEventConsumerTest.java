package com.tiktok.notificationservice.event.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.notificationservice.client.VideoOwnerClient;
import com.tiktok.notificationservice.entity.NotificationType;
import com.tiktok.notificationservice.repository.ProcessedEventRepository;
import com.tiktok.notificationservice.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InteractionEventConsumerTest {

    private static final byte[] CREATED = "CommentCreatedEvent".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DELETED = "CommentDeletedEvent".getBytes(StandardCharsets.UTF_8);

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private VideoOwnerClient videoOwnerClient;

    @Mock
    private NotificationService notificationService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private InteractionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);
        consumer = new InteractionEventConsumer(
                new IdempotentEventProcessor(processedEventRepository),
                videoOwnerClient, notificationService, objectMapper);
    }

    private String json(Object event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void onLike_notifiesTheVideoOwner() throws Exception {
        when(videoOwnerClient.ownerOf(7L)).thenReturn(50L);

        consumer.onLike(json(VideoLikeEvent.of(7L, 9L, true)));

        verify(notificationService).create(eq(50L), eq(9L), eq(NotificationType.LIKE), any(), any(), eq("7"));
    }

    @Test
    void onLike_ignoresAnUnlike() throws Exception {
        consumer.onLike(json(VideoLikeEvent.of(7L, 9L, false)));

        verify(videoOwnerClient, never()).ownerOf(any());
        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onLike_doesNotNotifyTheOwnerAboutTheirOwnLike() throws Exception {
        when(videoOwnerClient.ownerOf(7L)).thenReturn(9L);

        consumer.onLike(json(VideoLikeEvent.of(7L, 9L, true)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onLike_dropsTheNotificationWhenTheOwnerCannotBeResolved() throws Exception {
        when(videoOwnerClient.ownerOf(7L)).thenReturn(null);

        consumer.onLike(json(VideoLikeEvent.of(7L, 9L, true)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onLike_skipsAnEventAlreadyProcessed() throws Exception {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(false);

        consumer.onLike(json(VideoLikeEvent.of(7L, 9L, true)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onComment_notifiesTheVideoOwnerForATopLevelComment() throws Exception {
        when(videoOwnerClient.ownerOf(7L)).thenReturn(50L);

        consumer.onComment(json(CommentCreatedEvent.of(1L, 7L, 9L, "nice")), CREATED);

        verify(notificationService).create(eq(50L), any(), eq(NotificationType.COMMENT), any(), any(), eq("7"));
    }

    @Test
    void onComment_notifiesTheAuthorBeingRepliedToInsteadOfTheOwner() throws Exception {
        consumer.onComment(json(CommentCreatedEvent.of(2L, 7L, 9L, "agreed", 1L, 33L)), CREATED);

        verify(notificationService).create(eq(33L), any(), eq(NotificationType.COMMENT), any(), any(), eq("7"));
        verify(videoOwnerClient, never()).ownerOf(any());
    }

    /**
     * The deletion parses cleanly into CommentCreatedEvent with every missing field null, so
     * without the header check this announced a comment that had just been removed.
     */
    @Test
    void onComment_ignoresADeletionOnTheSharedTopic() throws Exception {
        consumer.onComment(json(CommentDeletedEvent.of(1L, 7L, 9L)), DELETED);

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onComment_treatsAMissingHeaderAsACreation() throws Exception {
        when(videoOwnerClient.ownerOf(7L)).thenReturn(50L);

        consumer.onComment(json(CommentCreatedEvent.of(1L, 7L, 9L, "nice")), null);

        verify(notificationService).create(eq(50L), any(), eq(NotificationType.COMMENT), any(), any(), eq("7"));
    }

    @Test
    void onShare_notifiesTheVideoOwner() throws Exception {
        when(videoOwnerClient.ownerOf(7L)).thenReturn(50L);

        consumer.onShare(json(VideoSharedEvent.of(3L, 7L, 9L)));

        verify(notificationService).create(eq(50L), any(), eq(NotificationType.SHARE), any(), any(), eq("7"));
    }
}
