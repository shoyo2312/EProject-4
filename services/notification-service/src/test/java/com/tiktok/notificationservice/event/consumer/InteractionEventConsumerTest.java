package com.tiktok.notificationservice.event.consumer;

import tools.jackson.databind.ObjectMapper;
import com.tiktok.event.interaction.CommentCreatedEvent;
import com.tiktok.event.interaction.CommentDeletedEvent;
import com.tiktok.event.interaction.CommentLikeChangedEvent;
import com.tiktok.event.interaction.VideoLikeEvent;
import com.tiktok.event.interaction.VideoRepostedEvent;
import com.tiktok.event.interaction.VideoSharedEvent;
import com.tiktok.notificationservice.client.BlockClient;
import com.tiktok.notificationservice.client.UserHandleClient;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private BlockClient blockClient;

    @Mock
    private UserHandleClient userHandleClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private InteractionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        when(processedEventRepository.tryClaim(anyString(), anyString())).thenReturn(true);
        consumer = new InteractionEventConsumer(
                new IdempotentEventProcessor(processedEventRepository),
                videoOwnerClient, notificationService, objectMapper, blockClient, userHandleClient);
    }

    private String json(Object event) throws Exception {
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void onLike_acrossABlock_notifiesNobody() throws Exception {
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);
        when(blockClient.isBlockedBetween(50L, 9L)).thenReturn(true);

        consumer.onLike(json(VideoLikeEvent.of(7L, 9L, true)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onLike_notifiesTheVideoOwner() throws Exception {
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);

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
        when(videoOwnerClient.ownerOf("7")).thenReturn(9L);

        consumer.onLike(json(VideoLikeEvent.of(7L, 9L, true)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onLike_dropsTheNotificationWhenTheOwnerCannotBeResolved() throws Exception {
        when(videoOwnerClient.ownerOf("7")).thenReturn(null);

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
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);

        consumer.onComment(json(CommentCreatedEvent.of(1L, 7L, 9L, "nice")), CREATED);

        verify(notificationService).create(eq(50L), any(), eq(NotificationType.COMMENT), any(), any(), eq("7"));
    }

    @Test
    void onComment_notifiesTheAuthorBeingRepliedToInsteadOfTheOwner() throws Exception {
        consumer.onComment(json(CommentCreatedEvent.of(2L, 7L, 9L, "agreed", 1L, 33L)), CREATED);

        verify(notificationService).create(eq(33L), any(), eq(NotificationType.COMMENT), any(), any(), eq("7"));
        verify(videoOwnerClient, never()).ownerOf(any());
    }

    @Test
    void onComment_notifiesTheTopLevelAuthorOfADirectReply() throws Exception {
        consumer.onComment(json(CommentCreatedEvent.of(2L, 7L, 9L, "agreed", 1L, null, 44L)), CREATED);

        verify(notificationService).create(eq(44L), eq(9L), eq(NotificationType.COMMENT), any(), any(), eq("7"));
        verify(videoOwnerClient, never()).ownerOf(any());
    }

    /**
     * Everyone tagged hears about it, as a COMMENT titled {@link InteractionEventConsumer#MENTION_TITLE}
     * — not a type of its own, which the mobile inbox would fail on. The owner already got the
     * comment itself, and the commenter tagging themselves is not news.
     */
    @Test
    void onComment_notifiesEachTaggedAccount_butNotTheOwnerTwiceOrTheCommenter() throws Exception {
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);
        when(userHandleClient.idsByUsername(List.of("alice", "owner", "me", "ghost")))
                .thenReturn(Map.of("alice", 60L, "owner", 50L, "me", 9L));

        consumer.onComment(json(CommentCreatedEvent.of(1L, 7L, 9L, "@alice @Owner @me @ghost look")), CREATED);

        verify(notificationService).create(eq(60L), eq(9L), eq(NotificationType.COMMENT),
                eq(InteractionEventConsumer.MENTION_TITLE), eq("@alice @Owner @me @ghost look"), eq("7"));
        verify(notificationService).create(eq(50L), eq(9L), eq(NotificationType.COMMENT),
                eq("New comment"), any(), eq("7"));
        verify(notificationService, never()).create(eq(50L), any(), any(), eq(InteractionEventConsumer.MENTION_TITLE), any(), any());
        verify(notificationService, never()).create(eq(9L), any(), any(), any(), any(), any());
    }

    /** No "@" in the text, no lookup — most comments tag nobody. */
    @Test
    void onComment_withoutATag_asksNobodyForHandles() throws Exception {
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);

        consumer.onComment(json(CommentCreatedEvent.of(1L, 7L, 9L, "nice")), CREATED);

        verifyNoInteractions(userHandleClient);
    }

    /** A block between the two stops a mention exactly as it stops a reply. */
    @Test
    void onComment_mentionAcrossABlock_notifiesNobody() throws Exception {
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);
        when(userHandleClient.idsByUsername(List.of("alice"))).thenReturn(Map.of("alice", 60L));
        when(blockClient.isBlockedBetween(60L, 9L)).thenReturn(true);

        consumer.onComment(json(CommentCreatedEvent.of(1L, 7L, 9L, "@alice hi")), CREATED);

        verify(notificationService, never()).create(eq(60L), any(), any(), any(), any(), any());
    }

    @Test
    void onCommentLike_notifiesTheCommentAuthor() throws Exception {
        consumer.onCommentLike(json(CommentLikeChangedEvent.of(1L, 7L, 9L, true, 3, 44L)));

        verify(notificationService).create(eq(44L), eq(9L), eq(NotificationType.COMMENT_LIKE), any(), any(), eq("7"));
    }

    @Test
    void onCommentLike_ignoresAnUnlike() throws Exception {
        consumer.onCommentLike(json(CommentLikeChangedEvent.of(1L, 7L, 9L, false, 2, 44L)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
    }

    @Test
    void onCommentLike_doesNotNotifyTheAuthorAboutTheirOwnLike() throws Exception {
        consumer.onCommentLike(json(CommentLikeChangedEvent.of(1L, 7L, 44L, true, 1, 44L)));

        verify(notificationService, never()).create(any(), any(), any(), any(), any(), any());
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
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);

        consumer.onComment(json(CommentCreatedEvent.of(1L, 7L, 9L, "nice")), null);

        verify(notificationService).create(eq(50L), any(), eq(NotificationType.COMMENT), any(), any(), eq("7"));
    }

    @Test
    void onShare_notifiesTheVideoOwner() throws Exception {
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);

        consumer.onShare(json(VideoSharedEvent.of(3L, 7L, 9L)));

        verify(notificationService).create(eq(50L), any(), eq(NotificationType.SHARE), any(), any(), eq("7"));
    }

    @Test
    void onRepost_notifiesTheVideoOwner() throws Exception {
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);

        consumer.onRepost(json(VideoRepostedEvent.of(7L, 9L, true)));

        verify(notificationService).create(50L, 9L, NotificationType.REPOST,
                "New repost", "Someone reposted your video.", "7");
    }

    /** Taking a repost back is not news, and nothing announced is withdrawn either — as with a like. */
    @Test
    void onRepost_anUndoIsNotNews() throws Exception {
        consumer.onRepost(json(VideoRepostedEvent.of(7L, 9L, false)));

        verifyNoInteractions(notificationService, videoOwnerClient);
    }

    /**
     * This group first meets the topic with no committed offset and reads from the earliest one, so
     * without a cutoff its first start would announce — and push — every retained repost as new.
     */
    @Test
    void onRepost_olderThanADay_isNotNews() throws Exception {
        when(videoOwnerClient.ownerOf("7")).thenReturn(50L);
        Instant yesterday = Instant.now().minus(Duration.ofHours(25));

        consumer.onRepost(json(new VideoRepostedEvent("old", yesterday, 7L, 9L, true)));

        verifyNoInteractions(notificationService, videoOwnerClient);
    }
}
