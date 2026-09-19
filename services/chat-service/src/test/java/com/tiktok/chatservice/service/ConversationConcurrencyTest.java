package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.mapper.MessageMapperImpl;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Both participants of a conversation write to the same document every time they send or read.
 * Written back whole under {@code @Version}, whichever request read the document second lost the
 * race with an OptimisticLockingFailureException — after its message was already stored, so the
 * sender got a 500, nobody got the broadcast, and the client's retry stored it twice.
 */
@DataMongoTest
@Testcontainers
class ConversationConcurrencyTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @Autowired
    private ConversationRepository conversationRepository;

    @Autowired
    private MessageRepository messageRepository;

    private final ConversationService conversationService = mock(ConversationService.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);

    private MessageServiceImpl messageService;

    @BeforeEach
    void setUp() {
        conversationRepository.deleteAll();
        messageRepository.deleteAll();
        messageService = new MessageServiceImpl(conversationService, conversationRepository,
                messageRepository, new MessageMapperImpl(), messagingTemplate, mock(BlockClient.class));
    }

    @Test
    void twoParticipantsSendingAtOnce_bothMessagesAreKeptAndBroadcast() {
        Conversation saved = conversationRepository.save(Conversation.builder()
                .id("c1").participantIds(List.of(1L, 2L)).participantKey("1_2").build());

        // Each request read the document before either wrote it back — the interleaving two
        // people typing at the same moment produce.
        Conversation readByOne = conversationRepository.findById(saved.getId()).orElseThrow();
        Conversation readByTwo = conversationRepository.findById(saved.getId()).orElseThrow();
        when(conversationService.requireParticipant(1L, "c1")).thenReturn(readByOne);
        when(conversationService.requireParticipant(2L, "c1")).thenReturn(readByTwo);

        messageService.sendMessage("c1", 1L, new SendMessageRequest("hi from 1"));
        messageService.sendMessage("c1", 2L, new SendMessageRequest("hi from 2"));

        assertThat(messageRepository.findAll()).hasSize(2);
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/conversations/c1"), any(Object.class));

        Conversation after = conversationRepository.findById("c1").orElseThrow();
        assertThat(after.getLastMessageContent()).isEqualTo("hi from 2");
        assertThat(after.getLastReadAt()).containsKeys("1", "2");
    }

    @Test
    void readingWhileTheOtherSideSends_neitherRequestFails() {
        Conversation saved = conversationRepository.save(Conversation.builder()
                .id("c2").participantIds(List.of(1L, 2L)).participantKey("1_2b").build());

        Conversation readBySender = conversationRepository.findById(saved.getId()).orElseThrow();
        Conversation readByReader = conversationRepository.findById(saved.getId()).orElseThrow();
        when(conversationService.requireParticipant(1L, "c2")).thenReturn(readBySender);
        when(conversationService.requireParticipant(2L, "c2")).thenReturn(readByReader);

        messageService.sendMessage("c2", 1L, new SendMessageRequest("hello"));
        messageService.markRead("c2", 2L);

        Conversation after = conversationRepository.findById("c2").orElseThrow();
        assertThat(after.getLastMessageContent()).isEqualTo("hello");
        assertThat(after.getLastReadAt()).containsKeys("1", "2");
    }
}
