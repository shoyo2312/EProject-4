package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.ChatEvent;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.exception.InvalidMessageException;
import com.tiktok.chatservice.exception.InvalidStickerException;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.exception.MessageRequestNotAcceptedException;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.mapper.MessageMapperImpl;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import com.tiktok.chatservice.sticker.StickerCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.mockito.Mockito.mock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Sending a sticker goes through the same method as text, so these check two things: that it comes
 * out as a stored, announced sticker, and that every rule that applies to text applies to it — and
 * that a bad payload is turned away before it can change anything about the conversation.
 * A = 1 sends, B = 2 receives.
 */
@ExtendWith(MockitoExtension.class)
class MessageStickerSendTest {

    private static final long A = 1L;
    private static final long B = 2L;

    @Mock
    private ConversationService conversationService;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private SimpMessagingTemplate messagingTemplate;
    @Mock
    private BlockClient blockClient;
    @Mock
    private ChatEventPublisher eventPublisher;

    private MessageServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new MessageServiceImpl(conversationService, conversationRepository, messageRepository,
                new MessageMapperImpl(), messagingTemplate, blockClient, eventPublisher,
                StickerCatalog.load("http://localhost:9000", "video-media"), org.mockito.Mockito.mock(ChatUserPrefsRepository.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.VideoClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.StoryClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.ShareStatsClient.class), mock(com.tiktok.chatservice.repository.ChatImageRepository.class), mock(com.tiktok.chatservice.service.ChatImageService.class));
        lenient().when(conversationService.promoteIfMutual(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Conversation.ConversationBuilder<?, ?> ordinary() {
        return Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2").initiatorId(A);
    }

    private static Conversation.ConversationBuilder<?, ?> request() {
        return ordinary().requestState(MessageRequestState.PENDING);
    }

    private void thread(Long viewer, Conversation conversation) {
        when(conversationService.requireParticipant(viewer, "c1")).thenReturn(conversation);
    }

    private MessageResponse sendSticker(long sender, String id) {
        return service.sendMessage("c1", sender, SendMessageRequest.sticker(id));
    }

    private void nothingChanged() {
        verify(messageRepository, never()).save(any());
        verify(messageRepository, never()).clearStaleRequestFlag(any(), any());
        verify(conversationRepository, never()).recordMessage(any(), any(), any(), any(), any());
        verify(conversationRepository, never()).rependRequest(any(), any(), any());
        verify(conversationService, never()).promoteIfMutual(any());
        verify(conversationService, never()).reopenForSend(any(), any());
        verifyNoInteractions(messagingTemplate, eventPublisher);
    }

    // --- sending ---------------------------------------------------------------------------

    @Test
    void aStickerIsStoredAsAStickerWithNoTextAndAnnouncedWithEverythingNeededToDrawIt() {
        thread(A, ordinary().build());

        MessageResponse sent = sendSticker(A, "party-popper");

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(MessageType.STICKER);
        assertThat(saved.getValue().getStickerId()).isEqualTo("party-popper");
        assertThat(saved.getValue().getContent()).isNull();

        assertThat(sent.type()).isEqualTo(MessageType.STICKER);
        assertThat(sent.content()).isNull();
        assertThat(sent.sticker().url()).isEqualTo("http://localhost:9000/video-media/stickers/fluent-3d/v1/party-popper.png");
        assertThat(sent.sticker().name()).isEqualTo("Party popper");

        // the conversation list's preview is the type, with no text to show
        verify(conversationRepository).recordMessage(eq("c1"), eq(A), eq(MessageType.STICKER), eq(null), any());
        verify(messagingTemplate).convertAndSend("/topic/conversations/c1", sent);
        verify(eventPublisher).toUser(eq(B), any(ChatEvent.class));
    }

    @Test
    void textStillWorksTheWayItAlwaysDid() {
        thread(A, ordinary().build());

        MessageResponse sent = service.sendMessage("c1", A, new SendMessageRequest("hello 😀"));

        assertThat(sent.type()).isEqualTo(MessageType.TEXT);
        assertThat(sent.content()).isEqualTo("hello 😀");
        assertThat(sent.sticker()).isNull();
        verify(conversationRepository).recordMessage(eq("c1"), eq(A), eq(MessageType.TEXT), eq("hello 😀"), any());
    }

    // --- a bad payload changes nothing ---------------------------------------------------------

    @Test
    void anUnknownStickerIsRefusedBeforeAnythingChanges() {
        thread(A, ordinary().build());

        assertThatThrownBy(() -> sendSticker(A, "not-a-sticker")).isInstanceOf(InvalidStickerException.class);
        nothingChanged();
    }

    @Test
    void contradictoryAndEmptyPayloadsAreRefusedBeforeAnythingChanges() {
        thread(A, ordinary().build());

        assertThatThrownBy(() -> service.sendMessage("c1", A, new SendMessageRequest(MessageType.STICKER, "hi", "fire")))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> service.sendMessage("c1", A, new SendMessageRequest(MessageType.TEXT, "hi", "fire")))
                .isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> service.sendMessage("c1", A, new SendMessageRequest("   ")))
                .extracting("code").isEqualTo("VALIDATION_ERROR");
        nothingChanged();
    }

    /** A wrong sticker must not reopen a deleted request on its way to being refused. */
    @Test
    void aBadStickerDoesNotReopenAThreadTheSenderDeleted() {
        thread(B, request().requestState(MessageRequestState.DECLINED).lastMessageAt(Instant.now()).build());

        assertThatThrownBy(() -> sendSticker(B, "nope")).isInstanceOf(InvalidStickerException.class);
        nothingChanged();
    }

    /** ...nor spend the one message of a request, nor restart a finished round. */
    @Test
    void aBadStickerDoesNotSpendOrRestartARequestRound() {
        thread(A, request().build());
        assertThatThrownBy(() -> sendSticker(A, "nope")).isInstanceOf(InvalidStickerException.class);
        nothingChanged();
    }

    @Test
    void aBadStickerAfterFifteenDaysDoesNotClearTheFinishedRound() {
        thread(A, request().requestState(MessageRequestState.DECLINED)
                .lastMessageAt(Instant.now().minus(Duration.ofDays(20))).build());

        assertThatThrownBy(() -> sendSticker(A, "nope")).isInstanceOf(InvalidStickerException.class);
        nothingChanged();
    }

    // --- who may send ----------------------------------------------------------------------

    @Test
    void aNonParticipantCannotSendAStickerEither() {
        when(conversationService.requireParticipant(99L, "c1")).thenThrow(new NotConversationParticipantException("c1"));

        assertThatThrownBy(() -> sendSticker(99L, "fire")).isInstanceOf(NotConversationParticipantException.class);
        nothingChanged();
    }

    @Test
    void aBlockStopsStickersToo() {
        thread(A, ordinary().build());
        doThrow(new MessagingBlockedException()).when(blockClient).requireNotBlocked(A, B);

        assertThatThrownBy(() -> sendSticker(A, "fire")).isInstanceOf(MessagingBlockedException.class);
        nothingChanged();
    }

    @Test
    void theRecipientCannotReplyWithAStickerBeforeAccepting() {
        thread(B, request().lastMessageAt(Instant.now()).build());

        assertThatThrownBy(() -> sendSticker(B, "fire")).isInstanceOf(MessageRequestNotAcceptedException.class);
        verify(messageRepository, never()).save(any());
    }

    // --- request rules -----------------------------------------------------------------------

    @Test
    void aStickerCanBeTheOneMessageOfARequest() {
        thread(A, request().build());

        sendSticker(A, "grinning-face");

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestMessage()).isTrue();
        assertThat(saved.getValue().getType()).isEqualTo(MessageType.STICKER);
    }

    @Test
    void aSecondStickerBeforeFifteenDaysIsRefused() {
        thread(A, request().lastMessageAt(Instant.now().minus(Duration.ofDays(3))).build());

        assertThatThrownBy(() -> sendSticker(A, "fire")).isInstanceOf(MessageRequestLimitReachedException.class);
        verify(messageRepository, never()).save(any());
    }

    @Test
    void afterFifteenDaysAStickerStartsANewRoundLikeTextDoes() {
        thread(A, request().requestState(MessageRequestState.DECLINED)
                .lastMessageAt(Instant.now().minus(Duration.ofDays(16))).build());

        sendSticker(A, "sparkles");

        verify(messageRepository).clearStaleRequestFlag(eq("c1"), any());
        verify(conversationRepository).rependRequest(eq("c1"), eq(A), any());
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestMessage()).isTrue();
    }

    @Test
    void aStickerFromTheDeciderReopensTheThreadLikeTextDoes() {
        Conversation declined = request().requestState(MessageRequestState.DECLINED).lastMessageAt(Instant.now()).build();
        thread(B, declined);
        when(conversationService.reopenForSend(declined, B)).thenReturn(ordinary().lastMessageAt(declined.getLastMessageAt()).build());

        sendSticker(B, "hugging");

        verify(conversationService).reopenForSend(declined, B);
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestMessage()).isNull();
    }

    // --- history ---------------------------------------------------------------------------------

    @Test
    void aMessageStoredBeforeTypesExistedReadsAsText() {
        Message legacy = Message.builder().id("m0").conversationId("c1").senderId(B).content("old message")
                .sentAt(Instant.now()).build();

        assertThat(legacy.getType()).isEqualTo(MessageType.TEXT);
        MessageResponse response = new MessageMapperImpl().toResponse(legacy);
        assertThat(response.type()).isEqualTo(MessageType.TEXT);
        assertThat(response.content()).isEqualTo("old message");
        assertThat(response.sticker()).isNull();
    }
}
