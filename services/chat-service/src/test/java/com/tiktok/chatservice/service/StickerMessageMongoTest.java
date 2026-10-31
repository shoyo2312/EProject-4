package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.DmAudience;
import com.tiktok.chatservice.client.FriendshipClient;
import com.tiktok.chatservice.client.SettingsClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.mapper.ConversationMapperImpl;
import com.tiktok.chatservice.mapper.MessageMapperImpl;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import com.tiktok.chatservice.sticker.StickerCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Stickers against a real MongoDB: what history, the conversation preview and the request limit see after a round trip. */
@DataMongoTest
@Testcontainers
class StickerMessageMongoTest {

    private static final long A = 1L;
    private static final long B = 2L;

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    @Autowired
    private ConversationRepository conversationRepository;
    @Autowired
    private MessageRepository messageRepository;
    @Autowired
    private ChatUserPrefsRepository prefsRepository;

    private final StickerCatalog catalog = StickerCatalog.load("http://localhost:9000", "video-media");
    private ConversationServiceImpl conversationService;
    private MessageServiceImpl messageService;

    @BeforeEach
    void setUp() {
        conversationRepository.deleteAll();
        messageRepository.deleteAll();
        SettingsClient settings = mock(SettingsClient.class);
        when(settings.dmAudience(any())).thenReturn(DmAudience.EVERYONE);
        FriendshipClient friends = mock(FriendshipClient.class);
        when(friends.areFriends(any(), any())).thenReturn(false);
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        conversationService = new ConversationServiceImpl(conversationRepository, mock(BlockClient.class), settings, friends,
                publisher, new ConversationViews(new ConversationMapperImpl(), messageRepository, prefsRepository, mock(BlockClient.class)));
        messageService = new MessageServiceImpl(conversationService, conversationRepository, messageRepository,
                new MessageMapperImpl(), mock(SimpMessagingTemplate.class), mock(BlockClient.class), publisher, catalog, prefsRepository,
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.VideoClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.StoryClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.ShareStatsClient.class), mock(com.tiktok.chatservice.repository.ChatImageRepository.class), mock(com.tiktok.chatservice.service.ChatImageService.class));
    }

    private Conversation ordinary() {
        return conversationRepository.save(Conversation.builder().id("c1").participantIds(List.of(A, B))
                .participantKey("1_2").initiatorId(A).build());
    }

    @Test
    void historyMixesOlderMessagesWithoutATypeAndStickersAndRendersBoth() {
        ordinary();
        // Stored the way messages were before types existed: no type, no stickerId.
        messageRepository.save(Message.builder().id("100").conversationId("c1").senderId(B).content("from the old days")
                .sentAt(Instant.parse("2026-10-01T08:00:00Z")).build());
        messageService.sendMessage("c1", A, new SendMessageRequest("hello 😀"));
        messageService.sendMessage("c1", A, SendMessageRequest.sticker("heart-eyes"));

        MessagePageResponse page = messageService.listMessages("c1", A, null, 20);

        List<MessageResponse> newestFirst = page.items();
        assertThat(newestFirst).hasSize(3);
        assertThat(newestFirst.get(0).type()).isEqualTo(MessageType.STICKER);
        assertThat(newestFirst.get(0).content()).isNull();
        assertThat(newestFirst.get(0).sticker().stickerId()).isEqualTo("heart-eyes");
        assertThat(newestFirst.get(1).type()).isEqualTo(MessageType.TEXT);
        assertThat(newestFirst.get(1).content()).isEqualTo("hello 😀");
        assertThat(newestFirst.get(2).type()).isEqualTo(MessageType.TEXT);
        assertThat(newestFirst.get(2).content()).isEqualTo("from the old days");
        assertThat(newestFirst.get(2).sticker()).isNull();
    }

    /** What the list shows after a reload must be what the live message said. */
    @Test
    void thePreviewAfterReloadMatchesWhatWasSent() {
        ordinary();

        messageService.sendMessage("c1", A, new SendMessageRequest("see you"));
        ConversationResponse afterText = conversationService.getById(B, "c1");
        assertThat(afterText.lastMessageType()).isEqualTo(MessageType.TEXT);
        assertThat(afterText.lastMessageContent()).isEqualTo("see you");

        messageService.sendMessage("c1", A, SendMessageRequest.sticker("party-popper"));
        ConversationResponse afterSticker = conversationService.getById(B, "c1");
        assertThat(afterSticker.lastMessageType()).isEqualTo(MessageType.STICKER);
        assertThat(afterSticker.lastMessageContent()).isNull();
        assertThat(afterSticker.hasUnread()).isTrue();

        messageService.sendMessage("c1", A, new SendMessageRequest("and text again"));
        ConversationResponse afterAgain = conversationService.getById(B, "c1");
        assertThat(afterAgain.lastMessageType()).isEqualTo(MessageType.TEXT);
        assertThat(afterAgain.lastMessageContent()).isEqualTo("and text again");
    }

    @Test
    void aConversationWhosePreviewPredatesTypesIsText() {
        conversationRepository.save(Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2")
                .lastMessageContent("old preview").lastMessageSenderId(B).lastMessageAt(Instant.parse("2026-10-01T08:00:00Z"))
                .build());

        ConversationResponse view = conversationService.getById(A, "c1");

        assertThat(view.lastMessageType()).isEqualTo(MessageType.TEXT);
        assertThat(view.lastMessageContent()).isEqualTo("old preview");
    }

    @Test
    void aStickerWhoseIdIsNoLongerInTheCatalogStillComesBackAsASticker() {
        ordinary();
        messageRepository.save(Message.builder().id("200").conversationId("c1").senderId(B).type(MessageType.STICKER)
                .stickerId("removed-long-ago").sentAt(Instant.now()).build());

        MessageResponse response = messageService.listMessages("c1", A, null, 20).items().get(0);

        assertThat(response.type()).isEqualTo(MessageType.STICKER);
        assertThat(response.stickerId()).isEqualTo("removed-long-ago");
        assertThat(response.sticker()).isNull();
    }

    /** A sticker is a message like any other: as a request's one message it is held by the same unique index. */
    @Test
    void racingStickerSendsAsTheFirstMessageOfARequestStoreExactlyOne() throws Exception {
        conversationRepository.save(Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2")
                .initiatorId(A).requestState(MessageRequestState.PENDING).build());
        List<Callable<Object>> sends = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            sends.add(() -> messageService.sendMessage("c1", A, SendMessageRequest.sticker("fire")));
        }

        ExecutorService pool = Executors.newFixedThreadPool(sends.size());
        CountDownLatch ready = new CountDownLatch(sends.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        for (Callable<Object> send : sends) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    send.call();
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            }));
        }
        ready.await();
        go.countDown();
        List<Throwable> outcomes = new ArrayList<>();
        for (Future<Throwable> future : futures) {
            outcomes.add(future.get());
        }
        pool.shutdown();

        assertThat(outcomes.stream().filter(t -> t == null)).hasSize(1);
        assertThat(outcomes.stream().filter(t -> t != null))
                .allSatisfy(t -> assertThat(t).isInstanceOf(MessageRequestLimitReachedException.class));
        assertThat(messageRepository.findAll()).hasSize(1);
        assertThat(messageRepository.findAll().get(0).getType()).isEqualTo(MessageType.STICKER);
        assertThat(messageRepository.findAll().get(0).getRequestMessage()).isTrue();
    }
}
