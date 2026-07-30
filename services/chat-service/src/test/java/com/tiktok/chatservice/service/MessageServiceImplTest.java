package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.mapper.MessageMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
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
                conversationService, conversationRepository, messageRepository, messageMapper, messagingTemplate);
    }

    @Test
    void sendMessage_persistsUpdatesConversationAndBroadcasts() {
        Conversation conversation = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).build();
        when(conversationService.requireParticipant(1L, "c1")).thenReturn(conversation);
        when(messageMapper.toResponse(any(Message.class)))
                .thenReturn(new MessageResponse("m1", "c1", 1L, "hi", null));

        MessageResponse response = messageService.sendMessage("c1", 1L, new SendMessageRequest("hi"));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepository).save(messageCaptor.capture());
        assertThat(messageCaptor.getValue().getSenderId()).isEqualTo(1L);
        assertThat(messageCaptor.getValue().getContent()).isEqualTo("hi");

        assertThat(conversation.getLastMessageContent()).isEqualTo("hi");
        assertThat(conversation.getLastMessageSenderId()).isEqualTo(1L);
        verify(conversationRepository).save(conversation);

        verify(messagingTemplate).convertAndSend("/topic/conversations/c1", response);
    }

    @Test
    void markRead_updatesLastReadAtForCurrentUser() {
        Conversation conversation = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).build();
        when(conversationService.requireParticipant(2L, "c1")).thenReturn(conversation);

        messageService.markRead("c1", 2L);

        assertThat(conversation.getLastReadAt()).containsKey("2");
        verify(conversationRepository).save(conversation);
    }

    @Test
    void listMessages_requiresParticipant() {
        Conversation conversation = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).build();
        when(conversationService.requireParticipant(1L, "c1")).thenReturn(conversation);
        when(messageRepository.findByConversationIdAndDeletedAtIsNullAndSentAtBefore(anyString(), any(), any()))
                .thenReturn(List.of());

        var page = messageService.listMessages("c1", 1L, null, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }
}
