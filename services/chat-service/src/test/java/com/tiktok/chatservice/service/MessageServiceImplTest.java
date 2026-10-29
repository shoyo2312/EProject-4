package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
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
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceImplTest {

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

    private MessageServiceImpl messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageServiceImpl(
                conversationService, conversationRepository, messageRepository, messageMapper, messagingTemplate,
                blockClient, eventPublisher, com.tiktok.chatservice.sticker.StickerCatalog.load("http://localhost:9000", "video-media"), org.mockito.Mockito.mock(ChatUserPrefsRepository.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.VideoClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.ShareStatsClient.class));
        // Nothing here is a pending request, so promotion is the identity.
        org.mockito.Mockito.lenient().when(conversationService.promoteIfMutual(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Mock
    private ChatEventPublisher eventPublisher;

    @Mock
    private BlockClient blockClient;

    /** A conversation opened before the block is still there; the block has to stop what goes into it. */
    @Test
    void sendMessage_acrossABlock_isRefusedAndNothingIsStoredOrBroadcast() {
        Conversation conversation = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).build();
        when(conversationService.requireParticipant(1L, "c1")).thenReturn(conversation);
        doThrow(new MessagingBlockedException()).when(blockClient).requireNotBlocked(1L, 2L);

        assertThatThrownBy(() -> messageService.sendMessage("c1", 1L, new SendMessageRequest("hi")))
                .isInstanceOf(MessagingBlockedException.class);

        verify(messageRepository, never()).save(any());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void sendMessage_persistsUpdatesConversationAndBroadcasts() {
        Conversation conversation = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).build();
        when(conversationService.requireParticipant(1L, "c1")).thenReturn(conversation);
        when(messageMapper.toResponse(any(Message.class)))
                .thenReturn(new MessageResponse("m1", "c1", 1L, "hi", null, com.tiktok.chatservice.entity.MessageType.TEXT, null, null));

        MessageResponse response = messageService.sendMessage("c1", 1L, new SendMessageRequest("hi"));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSenderId()).isEqualTo(1L);
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("hi");

        verify(conversationRepository).recordMessage(eq("c1"), eq(1L), eq(com.tiktok.chatservice.entity.MessageType.TEXT), eq("hi"), any());

        verify(messagingTemplate).convertAndSend("/topic/conversations/c1", response);
    }

    @Test
    void markRead_withNothingFromTheOtherSide_changesNothing() {
        Conversation conversation = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).build();
        when(conversationService.requireParticipant(2L, "c1")).thenReturn(conversation);

        messageService.markRead("c1", 2L, null);

        verify(conversationRepository, never()).advanceMark(any(), any(), any(), any(), any(), any());
    }

    @Test
    void listMessages_requiresParticipant() {
        Conversation conversation = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).build();
        when(conversationService.requireParticipant(1L, "c1")).thenReturn(conversation);
        when(messageRepository.findPage("c1", 1L, null, null, null, 21)).thenReturn(List.of());

        var page = messageService.listMessages("c1", 1L, null, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }
}
