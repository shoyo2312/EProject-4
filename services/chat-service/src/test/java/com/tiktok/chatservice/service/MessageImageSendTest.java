package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.ShareStatsClient;
import com.tiktok.chatservice.client.StoryClient;
import com.tiktok.chatservice.client.VideoClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.ImageView;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.dto.response.MessageUpdateResponse;
import com.tiktok.chatservice.dto.response.ReplyQuote;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.exception.InvalidMessageException;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.chatservice.mapper.MessageMapperImpl;
import com.tiktok.chatservice.repository.ChatImageRepository;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import com.tiktok.chatservice.sticker.StickerCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageImageSendTest {
    private final ConversationService conversations = mock(ConversationService.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final BlockClient blocks = mock(BlockClient.class);
    private final ChatEventPublisher events = mock(ChatEventPublisher.class);
    private final SimpMessagingTemplate topic = mock(SimpMessagingTemplate.class);
    private final ChatImageRepository images = mock(ChatImageRepository.class);
    private final ChatImageService imageService = mock(ChatImageService.class);
    private final MessageServiceImpl service = new MessageServiceImpl(conversations, conversationRepository, messages,
            new MessageMapperImpl(), topic, blocks, events, StickerCatalog.load("http://localhost:9000", "video-media"),
            mock(ChatUserPrefsRepository.class), mock(VideoClient.class), mock(StoryClient.class),
            mock(ShareStatsClient.class), images, imageService);
    private static final ImageView VIEW = new ImageView("img1", "/api/v1/conversations/c/images/img1", "image/png", 400, 300, 10);
    private Conversation thread;

    @BeforeEach
    void setup() {
        thread = Conversation.builder().id("c").participantIds(List.of(1L, 2L)).initiatorId(1L).build();
        when(conversations.requireParticipant(1L, "c")).thenAnswer(i -> thread);
        lenient().when(conversations.promoteIfMutual(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(images.claim(eq("c"), eq("img1"), eq(1L), any())).thenReturn(true);
        lenient().when(imageService.views(eq("c"), anyCollection())).thenReturn(Map.of("img1", VIEW));
    }

    private MessageResponse send(String key) {
        return service.sendMessage("c", 1L, SendMessageRequest.image("img1", "  look  ", key));
    }

    @Test
    void anImageIsOneMessageWithItsCaptionAndPictureDeliveredToBoth() {
        MessageResponse response = send("k");
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(MessageType.IMAGE);
        assertThat(saved.getValue().getImageId()).isEqualTo("img1");
        assertThat(saved.getValue().getContent()).isEqualTo("look");
        assertThat(saved.getValue().getClientRequestId()).isEqualTo("k");
        assertThat(response.image()).isEqualTo(VIEW);
        // The claim names the very message that was stored.
        verify(images).claim("c", "img1", 1L, saved.getValue().getId());
        verify(topic).convertAndSend(eq("/topic/conversations/c"), any(MessageResponse.class));
        verify(events).toUser(eq(2L), any());
    }

    @Test
    void thePreviewNeverCarriesTheCaption() {
        send("k");
        verify(conversationRepository).recordMessage(eq("c"), eq(1L), eq(MessageType.IMAGE), eq("look"), any());
    }

    @Test
    void anImageThatIsNotTheSendersOrAlreadyUsedIsRefusedWithNothingStored() {
        when(images.claim(eq("c"), eq("img1"), eq(1L), any())).thenReturn(false);
        assertThatThrownBy(() -> send("k")).isInstanceOf(InvalidMessageException.class);
        verify(messages, never()).save(any());
        verifyNoInteractions(topic, events);
        verify(conversationRepository, never()).recordMessage(any(), any(), any(), any(), any());
    }

    @Test
    void aBlockStopsAnImageLikeAnyMessageBeforeAnythingIsClaimed() {
        doThrow(new MessagingBlockedException()).when(blocks).requireNotBlocked(1L, 2L);
        assertThatThrownBy(() -> send("k")).isInstanceOf(MessagingBlockedException.class);
        verifyNoInteractions(images);
        verify(messages, never()).save(any());
    }

    @Test
    void aRepeatedSendReturnsTheFirstMessageWithoutClaimingOrDeliveringAgain() {
        Message existing = Message.builder().id("m1").conversationId("c").senderId(1L).type(MessageType.IMAGE)
                .imageId("img1").content("look").sentAt(Instant.now()).build();
        when(messages.findByConversationIdAndSenderIdAndClientRequestId("c", 1L, "k")).thenReturn(Optional.of(existing));
        MessageResponse again = send("k");
        assertThat(again.id()).isEqualTo("m1");
        assertThat(again.replayed()).isTrue();
        verify(images, never()).claim(any(), any(), any(), any());
        verify(messages, never()).save(any());
        verifyNoInteractions(topic, events);
    }

    @Test
    void aLostDedupeRaceReleasesTheClaimAndReturnsTheWinner() {
        Message winner = Message.builder().id("winner").conversationId("c").senderId(1L).type(MessageType.IMAGE)
                .imageId("img1").sentAt(Instant.now()).build();
        when(messages.findByConversationIdAndSenderIdAndClientRequestId("c", 1L, "k"))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(messages.save(any())).thenThrow(new DuplicateKeyException("message_client_request_idx"));
        assertThat(send("k").id()).isEqualTo("winner");
        ArgumentCaptor<String> claimed = ArgumentCaptor.forClass(String.class);
        verify(images).claim(eq("c"), eq("img1"), eq(1L), claimed.capture());
        verify(images).release(claimed.getValue());
        verifyNoInteractions(topic, events);
    }

    @Test
    void aMessageRequestStillAllowsOnlyOneMessageAndAnImageIsThatMessage() {
        thread = Conversation.builder().id("c").participantIds(List.of(1L, 2L)).initiatorId(1L)
                .requestState(MessageRequestState.PENDING).build();
        send("k");
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).save(saved.capture());
        assertThat(saved.getValue().getRequestMessage()).isTrue();

        when(messages.save(any())).thenThrow(new DuplicateKeyException("one_request_message_idx"));
        assertThatThrownBy(() -> send("other")).isInstanceOf(MessageRequestLimitReachedException.class);
        verify(images, times(2)).claim(any(), any(), any(), any());
        verify(images).release(any());
    }

    @Test
    void imageFieldsAreRefusedOnOtherTypesAndRequiredOnImages() {
        assertThatThrownBy(() -> service.sendMessage("c", 1L,
                new SendMessageRequest(MessageType.TEXT, "hi", null, null, null, null, null, "img1")))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> service.sendMessage("c", 1L,
                new SendMessageRequest(MessageType.IMAGE, "hi", null, null, null, null, null, null)))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> service.sendMessage("c", 1L,
                new SendMessageRequest(MessageType.IMAGE, null, "fire", null, null, null, null, "img1")))
                .isInstanceOf(InvalidMessageException.class);
        verify(messages, never()).save(any());
        verifyNoInteractions(images);
    }

    @Test
    void aReplyToAnImageQuotesItsPictureAndAReplyWithAnImageIsPrivate() {
        Message original = Message.builder().id("o").conversationId("c").senderId(2L).type(MessageType.IMAGE)
                .imageId("img1").content("cat").sentAt(Instant.now().minusSeconds(5)).build();
        when(messages.findInConversation("c", "o")).thenReturn(Optional.of(original));
        when(messages.findInConversation(eq("c"), anyCollection())).thenReturn(List.of(original));

        MessageResponse response = service.sendMessage("c", 1L, SendMessageRequest.image("img1", null, "k").replyingTo("o"));

        ReplyQuote quote = response.replyTo();
        assertThat(quote.state()).isEqualTo(ReplyQuote.State.OK);
        assertThat(quote.type()).isEqualTo(MessageType.IMAGE);
        assertThat(quote.image()).isEqualTo(VIEW);
        assertThat(quote.content()).isEqualTo("cat");
        verify(events).toUser(eq(1L), any());
        verify(events).toUser(eq(2L), any());
        verifyNoInteractions(topic);
    }

    @Test
    void recallingAnImageDeletesItsBytesAndAQuoteOfItSaysRecalled() {
        Message sent = Message.builder().id("m1").conversationId("c").senderId(1L).type(MessageType.IMAGE)
                .imageId("img1").sentAt(Instant.now()).build();
        Message recalled = Message.builder().id("m1").conversationId("c").senderId(1L).type(MessageType.IMAGE)
                .sentAt(sent.getSentAt()).recalledAt(Instant.now()).build();
        when(messages.findInConversation("c", "m1")).thenReturn(Optional.of(sent), Optional.of(recalled));
        when(messages.recall(eq("c"), eq("m1"), eq(1L), any())).thenReturn(true);
        when(conversationRepository.findByIdAndDeletedAtIsNull("c")).thenReturn(Optional.empty());

        MessageUpdateResponse update = service.recall("c", 1L, "m1");

        assertThat(update.recalledAt()).isNotNull();
        verify(images).deleteByMessageId("m1");
    }

    @Test
    void aRecallThatChangedNothingKeepsTheBytes() {
        Message sent = Message.builder().id("m1").conversationId("c").senderId(1L).type(MessageType.IMAGE)
                .imageId("img1").sentAt(Instant.now()).build();
        when(messages.findInConversation("c", "m1")).thenReturn(Optional.of(sent));
        when(messages.recall(eq("c"), eq("m1"), eq(1L), any())).thenReturn(false);
        service.recall("c", 1L, "m1");
        verify(images, never()).deleteByMessageId(any());
    }
}
