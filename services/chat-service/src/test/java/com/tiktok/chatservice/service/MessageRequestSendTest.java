package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.ChatEvent;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.exception.MessageRequestNotAcceptedException;
import com.tiktok.chatservice.mapper.MessageMapper;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What {@code sendMessage} does with a message request. A = 1 opened it, B = 2 received it. */
@ExtendWith(MockitoExtension.class)
class MessageRequestSendTest {

    private static final long A = 1L;
    private static final long B = 2L;

    @Mock
    private ConversationService conversationService;
    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private MessageMapper messageMapper;
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
                messageMapper, messagingTemplate, blockClient, eventPublisher, com.tiktok.chatservice.sticker.StickerCatalog.load("http://localhost:9000", "video-media"), org.mockito.Mockito.mock(ChatUserPrefsRepository.class));
        lenient().when(conversationService.promoteIfMutual(any())).thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(messageMapper.toResponse(any(Message.class)))
                .thenAnswer(invocation -> {
                    Message m = invocation.getArgument(0);
                    return new MessageResponse(m.getId(), m.getConversationId(), m.getSenderId(), m.getContent(), m.getSentAt(), m.getType(), m.getStickerId(), null);
                });
    }

    private static Conversation.ConversationBuilder<?, ?> request() {
        return Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2")
                .initiatorId(A).requestState(MessageRequestState.PENDING);
    }

    private void thread(Long viewer, Conversation conversation) {
        when(conversationService.requireParticipant(viewer, "c1")).thenReturn(conversation);
    }

    private MessageResponse send(long sender, String text) {
        return service.sendMessage("c1", sender, new SendMessageRequest(text));
    }

    @Test
    void theFirstMessageOfARequestIsStoredFlaggedAndAnnouncedToTheRecipient() {
        thread(A, request().build());

        MessageResponse sent = send(A, "hello");

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestMessage()).isTrue();
        verify(conversationRepository).recordMessage(eq("c1"), eq(A), eq(com.tiktok.chatservice.entity.MessageType.TEXT), eq("hello"), any());
        verify(messagingTemplate).convertAndSend("/topic/conversations/c1", sent);

        // The recipient does not know the conversation id yet, so it reaches them on their own queue.
        verify(eventPublisher).toUser(eq(B), any(ChatEvent.class));
        verify(conversationRepository).findByIdAndDeletedAtIsNull("c1");
    }

    @Test
    void aSecondMessageFromTheInitiatorIsRefusedAndNothingIsStoredOrBroadcast() {
        thread(A, request().lastMessageAt(Instant.now()).build());

        assertThatThrownBy(() -> send(A, "again")).isInstanceOf(MessageRequestLimitReachedException.class);

        verify(messageRepository, never()).save(any());
        verifyNoInteractions(messagingTemplate, eventPublisher);
        verify(conversationRepository, never()).recordMessage(any(), any(), any(), any(), any());
    }

    @Test
    void theRecipientCannotReplyBeforeAccepting_evenWithTheirOwnFirstMessage() {
        thread(B, request().lastMessageAt(Instant.now()).build());

        assertThatThrownBy(() -> send(B, "hi back")).isInstanceOf(MessageRequestNotAcceptedException.class);

        verify(messageRepository, never()).save(any());
        verifyNoInteractions(messagingTemplate, eventPublisher);
    }

    @Test
    void theRecipientCannotStartTheConversationEither() {
        thread(B, request().build());

        assertThatThrownBy(() -> send(B, "first!")).isInstanceOf(MessageRequestNotAcceptedException.class);
        verify(messageRepository, never()).save(any());
    }

    /** Another send used the allowance between the policy check and this save; the database said no. */
    @Test
    void aDuplicateFromTheUniqueIndexIsRefusedAndNothingIsBroadcast() {
        thread(A, request().build());
        when(messageRepository.save(any())).thenThrow(new DuplicateKeyException("one_request_message_idx"));

        assertThatThrownBy(() -> send(A, "raced")).isInstanceOf(MessageRequestLimitReachedException.class);

        verify(conversationRepository, never()).recordMessage(any(), any(), any(), any(), any());
        verifyNoInteractions(messagingTemplate, eventPublisher);
    }

    /** A save that fails for any other reason stores nothing, so nothing is spent and nothing is announced. */
    @Test
    void aFailedSaveBroadcastsNothingAndPropagates() {
        thread(A, request().build());
        when(messageRepository.save(any())).thenThrow(new IllegalStateException("mongo down"));

        assertThatThrownBy(() -> send(A, "hello")).isInstanceOf(IllegalStateException.class);

        verify(conversationRepository, never()).recordMessage(any(), any(), any(), any(), any());
        verifyNoInteractions(messagingTemplate, eventPublisher);
    }

    @Test
    void aDeletedRequestRefusesTheInitiatorWithoutSayingItWasDeleted() {
        thread(A, request().requestState(MessageRequestState.DECLINED).lastMessageAt(Instant.now()).build());

        assertThatThrownBy(() -> send(A, "hello?")).isInstanceOf(MessageRequestLimitReachedException.class);
        verify(messageRepository, never()).save(any());
    }

    /** Deleting and then writing is reaching out: the thread opens, and the message is an ordinary one. */
    @Test
    void theDeciderSendingReopensTheThreadAndStoresAnOrdinaryMessage() {
        Conversation declined = request().requestState(MessageRequestState.DECLINED).lastMessageAt(Instant.now()).build();
        Conversation reopened = Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2")
                .initiatorId(A).lastMessageAt(declined.getLastMessageAt()).build();
        thread(B, declined);
        when(conversationService.reopenForSend(declined, B)).thenReturn(reopened);

        send(B, "changed my mind");

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestMessage()).isNull();
        verify(conversationService).reopenForSend(declined, B);
    }

    @Test
    void ifTheOtherAccountsAudienceRefusesTheDeciderNothingIsStored() {
        Conversation declined = request().requestState(MessageRequestState.DECLINED).lastMessageAt(Instant.now()).build();
        thread(B, declined);
        when(conversationService.reopenForSend(declined, B))
                .thenThrow(new com.tiktok.chatservice.exception.MessagingNotAllowedException());

        assertThatThrownBy(() -> send(B, "hi")).isInstanceOf(com.tiktok.chatservice.exception.MessagingNotAllowedException.class);
        verify(messageRepository, never()).save(any());
    }

    // --- the fifteen-day retry ------------------------------------------------------------------

    @Test
    void anotherRequestMessageBeforeFifteenDaysIsRefused() {
        thread(A, request().lastMessageAt(Instant.now().minus(java.time.Duration.ofDays(14))).build());

        assertThatThrownBy(() -> send(A, "any news?")).isInstanceOf(MessageRequestLimitReachedException.class);
        verify(messageRepository, never()).clearStaleRequestFlag(any(), any());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void afterFifteenDaysTheInitiatorStartsANewRoundWithTheFinishedOneCleared() {
        thread(A, request().lastMessageAt(Instant.now().minus(java.time.Duration.ofDays(16))).build());

        send(A, "just checking in");

        verify(messageRepository).clearStaleRequestFlag(eq("c1"), any());
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(saved.capture());
        assertThat(saved.getValue().getRequestMessage()).isTrue();
        // Still pending, so nothing to put back in the recipient's box.
        verify(conversationRepository, never()).rependRequest(any(), any(), any());
    }

    @Test
    void aRetryOnADeletedRequestPutsItBackInTheRecipientsBox() {
        thread(A, request().requestState(MessageRequestState.DECLINED)
                .lastMessageAt(Instant.now().minus(java.time.Duration.ofDays(20))).build());

        send(A, "hello again");

        verify(conversationRepository).rependRequest(eq("c1"), eq(A), any());
    }

    @Test
    void aRetryThatLosesTheRaceStoresNothingAndRestoresNothing() {
        thread(A, request().requestState(MessageRequestState.DECLINED)
                .lastMessageAt(Instant.now().minus(java.time.Duration.ofDays(20))).build());
        when(messageRepository.save(any())).thenThrow(new DuplicateKeyException("one_request_message_idx"));

        assertThatThrownBy(() -> send(A, "hello again")).isInstanceOf(MessageRequestLimitReachedException.class);
        verify(conversationRepository, never()).rependRequest(any(), any(), any());
        verifyNoInteractions(messagingTemplate, eventPublisher);
    }

    @Test
    void afterAcceptingBothSidesSendWithoutTheFlag() {
        Conversation accepted = Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2")
                .initiatorId(A).lastMessageAt(Instant.now()).build();
        thread(A, accepted);
        thread(B, accepted);

        send(B, "thanks for asking");
        send(A, "np");

        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).allSatisfy(m -> assertThat(m.getRequestMessage()).isNull());
    }

    @Test
    void aLegacyConversationWithNoRequestFieldsSendsAsBefore() {
        thread(A, Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2").build());

        send(A, "hello");

        verify(messageRepository).save(any());
        verify(eventPublisher, never()).conversationChanged(any());
    }
}
