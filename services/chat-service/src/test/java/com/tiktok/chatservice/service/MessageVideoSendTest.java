package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.*;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.*;
import com.tiktok.chatservice.entity.*;
import com.tiktok.chatservice.exception.*;
import com.tiktok.chatservice.mapper.MessageMapperImpl;
import com.tiktok.chatservice.repository.*;
import com.tiktok.chatservice.sticker.StickerCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageVideoSendTest {
    private final ConversationService conversations = mock(ConversationService.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final BlockClient blocks = mock(BlockClient.class);
    private final VideoClient videos = mock(VideoClient.class);
    private final ShareStatsClient shares = mock(ShareStatsClient.class);
    private final ChatEventPublisher events = mock(ChatEventPublisher.class);
    private final SimpMessagingTemplate topic = mock(SimpMessagingTemplate.class);
    private final MessageServiceImpl service = new MessageServiceImpl(conversations, conversationRepository, messages,
            new MessageMapperImpl(), topic, blocks, events, StickerCatalog.load("http://localhost:9000", "video-media"),
            mock(ChatUserPrefsRepository.class), videos, mock(StoryClient.class), shares);
    private static final VideoCard CARD = new VideoCard("123", "AVAILABLE", 7L, "thumb", "title", 10);
    private Conversation thread;

    @BeforeEach
    void setup() {
        thread = Conversation.builder().id("c").participantIds(List.of(1L, 2L)).initiatorId(1L).build();
        when(conversations.requireParticipant(1L, "c")).thenAnswer(i -> thread);
        lenient().when(conversations.promoteIfMutual(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(videos.viewable(anyLong(), anyCollection())).thenReturn(Map.of("123", CARD));
        lenient().when(videos.hydrate(anyLong(), anyCollection())).thenReturn(Map.of("123", CARD));
    }

    private MessageResponse send(String key) {
        return service.sendMessage("c", 1L, SendMessageRequest.video("123", "  note  ", key));
    }

    @Test
    void captionAndVideoAreOneMessageDeliveredOnlyInPrivateFrames() {
        MessageResponse response = send("k");
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(MessageType.VIDEO);
        assertThat(saved.getValue().getContent()).isEqualTo("note");
        assertThat(saved.getValue().getVideoId()).isEqualTo("123");
        assertThat(saved.getValue().getClientRequestId()).isEqualTo("k");
        assertThat(response.video()).isEqualTo(CARD);
        verifyNoInteractions(topic);
        verify(events).toUser(eq(1L), any());
        verify(events).toUser(eq(2L), any());
        verify(shares).count("123", 1L, response.id());
    }

    @Test
    void senderCannotViewIs404WithNoStateChanges() {
        when(videos.viewable(1L, List.of("123"))).thenReturn(Map.of());
        assertThatThrownBy(() -> send("k")).isInstanceOf(VideoNotAvailableException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        assertNoMutation();
    }

    @Test
    void recipientCannotViewIs422WithNoStateChanges() {
        when(videos.viewable(2L, List.of("123"))).thenReturn(Map.of());
        assertThatThrownBy(() -> send("k")).isInstanceOf(VideoNotShareableToRecipientException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertNoMutation();
    }

    @Test
    void unavailableCheckIs503RatherThanDeleted() {
        when(videos.viewable(1L, List.of("123"))).thenThrow(new VideoCheckUnavailableException());
        assertThatThrownBy(() -> send("k")).isInstanceOf(VideoCheckUnavailableException.class)
                .extracting("code", "status").containsExactly("VIDEO_CHECK_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        assertNoMutation();
    }

    private void assertNoMutation() {
        verify(messages, never()).save(any());
        verify(conversations, never()).promoteIfMutual(any());
        verify(conversations, never()).reopenForSend(any(), any());
        verifyNoInteractions(conversationRepository, topic, events, shares);
    }

    @Test
    void firstRequestWithCaptionConsumesOnlyOneSlotAndItsRetryStillReturns() {
        thread = Conversation.builder().id("c").participantIds(List.of(1L, 2L)).initiatorId(1L)
                .requestState(MessageRequestState.PENDING).build();
        MessageResponse first = send("k");
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getRequestMessage()).isTrue();
        thread = Conversation.builder().id("c").participantIds(List.of(1L, 2L)).initiatorId(1L)
                .requestState(MessageRequestState.PENDING).lastMessageAt(first.sentAt()).build();
        when(messages.findByConversationIdAndSenderIdAndClientRequestId("c", 1L, "k"))
                .thenReturn(Optional.of(saved.getValue()));
        MessageResponse retried = send("k");
        assertThat(retried.id()).isEqualTo(first.id());
        assertThat(retried.replayed()).isTrue();
        var json = JsonMapper.builder().findAndAddModules().build();
        assertThat(json.writeValueAsString(retried)).isEqualTo(json.writeValueAsString(first));
        verify(messages, times(1)).save(any());
        verify(shares, times(1)).count(any(), any(), any());
        verify(events, times(2)).toUser(any(), any());
        assertThatThrownBy(() -> send("other")).isInstanceOf(MessageRequestLimitReachedException.class);
    }

    @Test
    void uniqueIndexRaceReturnsExistingWithoutDeliveryOrStats() {
        Message existing = video("existing", "123");
        when(messages.findByConversationIdAndSenderIdAndClientRequestId("c", 1L, "k"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(messages.save(any())).thenThrow(new DuplicateKeyException("message_client_request_idx"));
        assertThat(send("k").id()).isEqualTo("existing");
        verifyNoInteractions(events, shares, topic, conversationRepository);
    }

    @Test
    void singleRequestIndexStillRefusesADifferentDedupeKey() {
        when(messages.save(any())).thenThrow(new DuplicateKeyException("one_request_message_idx"));
        assertThatThrownBy(() -> send("k")).isInstanceOf(MessageRequestLimitReachedException.class);
        verifyNoInteractions(events, shares, topic, conversationRepository);
    }

    @Test
    void statsFailureDoesNotFailOrRepeatDelivery() {
        doThrow(new IllegalStateException("stats down")).when(shares).count(any(), any(), any());
        assertThat(send("k").video()).isEqualTo(CARD);
        verify(messages).save(any());
        verify(shares).count(any(), any(), any());
    }

    @Test
    void recipientFrameIsRenderedForRecipient() {
        when(videos.hydrate(eq(2L), anyCollection())).thenReturn(Map.of("123", VideoCard.unavailable("123")));
        assertThat(send(null).video().state()).isEqualTo("AVAILABLE");
        ArgumentCaptor<ChatEvent> frame = ArgumentCaptor.forClass(ChatEvent.class);
        verify(events).toUser(eq(2L), frame.capture());
        assertThat(frame.getValue().message().video().state()).isEqualTo("UNAVAILABLE");
        assertThat(frame.getValue().message().video().thumbnailUrl()).isNull();
        verifyNoInteractions(topic);
    }

    @Test
    void pageBatchesMessagesAndReplyOriginalsOnceForViewer() {
        Message a = video("a", "123");
        Message b = Message.builder().id("b").conversationId("c").senderId(2L).type(MessageType.TEXT)
                .content("reply").replyToMessageId("original").sentAt(Instant.now()).build();
        Message original = Message.builder().id("original").conversationId("c").senderId(1L)
                .type(MessageType.VIDEO).videoId("456").content("x".repeat(200)).sentAt(Instant.now()).build();
        when(messages.findPage("c", 1L, null, null, null, 21)).thenReturn(List.of(a, b));
        when(messages.findInConversation(eq("c"), anyCollection())).thenReturn(List.of(original));
        when(videos.hydrate(eq(1L), anyCollection())).thenReturn(Map.of("123", CARD, "456",
                new VideoCard("456", "AVAILABLE", 7L, "quote-thumb", "secret title", 20)));
        MessagePageResponse page = service.listMessages("c", 1L, null, 20);
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(videos).hydrate(eq(1L), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder("123", "456");
        ReplyQuote quote = page.items().get(1).replyTo();
        assertThat(quote.type()).isEqualTo(MessageType.VIDEO);
        assertThat(quote.content()).isEqualTo("x".repeat(140) + "\u2026");
        assertThat(quote.video().thumbnailUrl()).isEqualTo("quote-thumb");
        assertThat(quote.video().title()).isNull();
        assertThat(quote.video().ownerId()).isNull();
    }

    @Test
    void replyToVideoUsesPrivateFramesAndViewerSpecificQuote() {
        Message original = video("original", "123");
        when(messages.findInConversation("c", "original")).thenReturn(Optional.of(original));
        when(videos.hydrate(eq(2L), anyCollection())).thenReturn(Map.of("123", VideoCard.unknown("123")));
        MessageResponse response = service.sendMessage("c", 1L, new SendMessageRequest("reply").replyingTo("original"));
        assertThat(response.replyTo().type()).isEqualTo(MessageType.VIDEO);
        ArgumentCaptor<ChatEvent> frame = ArgumentCaptor.forClass(ChatEvent.class);
        verify(events).toUser(eq(2L), frame.capture());
        assertThat(frame.getValue().message().replyTo().video().state()).isEqualTo("UNKNOWN");
        assertThat(frame.getValue().message().replyTo().video().videoId()).isNull();
        verifyNoInteractions(topic, shares);
    }

    @Test
    void recalledVideoAndQuotesContainNoCaptionOrCard() {
        Message recalled = Message.builder().id("original").conversationId("c").senderId(1L)
                .type(MessageType.VIDEO).videoId("123").content("secret").sentAt(Instant.now())
                .recalledAt(Instant.now()).build();
        Message reply = Message.builder().id("reply").conversationId("c").senderId(2L).type(MessageType.TEXT)
                .content("reply").replyToMessageId("original").sentAt(Instant.now()).build();
        when(messages.findPage("c", 1L, null, null, null, 21)).thenReturn(List.of(recalled, reply));
        when(messages.findInConversation(eq("c"), anyCollection())).thenReturn(List.of(recalled));
        var page = service.listMessages("c", 1L, null, 20);
        assertThat(page.items().getFirst().content()).isNull();
        assertThat(page.items().getFirst().video()).isNull();
        assertThat(page.items().get(1).replyTo().state()).isEqualTo(ReplyQuote.State.RECALLED);
        assertThat(page.items().get(1).replyTo().video()).isNull();
        assertThat(page.items().get(1).replyTo().content()).isNull();
        verify(videos, never()).hydrate(any(), any());
    }

    private static Message video(String id, String videoId) {
        return Message.builder().id(id).conversationId("c").senderId(1L).type(MessageType.VIDEO)
                .videoId(videoId).content("note").sentAt(Instant.now()).build();
    }

    @Test
    void changingViewabilityIsReflectedOnEveryHistoryRead() {
        when(messages.findPage("c", 1L, null, null, null, 21)).thenReturn(List.of(video("original", "123")));
        when(videos.hydrate(eq(1L), anyCollection())).thenReturn(Map.of("123", CARD),
                Map.of("123", VideoCard.unavailable("123")));
        assertThat(service.listMessages("c", 1L, null, 20).items().getFirst().video().thumbnailUrl()).isEqualTo("thumb");
        var hidden = service.listMessages("c", 1L, null, 20).items().getFirst().video();
        assertThat(hidden.state()).isEqualTo("UNAVAILABLE");
        assertThat(hidden.thumbnailUrl()).isNull();
        verify(videos, times(2)).hydrate(eq(1L), anyCollection());
    }
}
