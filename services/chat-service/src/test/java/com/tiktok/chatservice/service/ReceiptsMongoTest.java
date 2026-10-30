package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.ReceiptView;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.mapper.MessageMapperImpl;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.ConversationRepositoryCustom.MarkKind;
import com.tiktok.chatservice.repository.MessageRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Against a real MongoDB: marks move only forward by position, ties in time are told apart by id,
 * sending is not reading, and the m1/m2 race of the specification comes out right.
 */
@DataMongoTest
@Testcontainers
class ReceiptsMongoTest {

    @Container
    @ServiceConnection
    static MongoDBContainer MONGO = new MongoDBContainer(DockerImageName.parse("mongo:7"));

    private static final Long A = 1L;
    private static final Long B = 2L;
    private static final Instant T0 = Instant.parse("2026-10-28T08:00:00Z");

    @Autowired private ConversationRepository conversations;
    @Autowired private MessageRepository messages;
    @Autowired private ChatUserPrefsRepository prefs;

    private final ConversationService conversationService = mock(ConversationService.class);
    private final ChatEventPublisher publisher = mock(ChatEventPublisher.class);
    private MessageServiceImpl service;

    @BeforeEach
    void setUp() {
        conversations.deleteAll();
        messages.deleteAll();
        conversations.save(Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2").build());
        service = new MessageServiceImpl(conversationService, conversations, messages, new MessageMapperImpl(),
                mock(SimpMessagingTemplate.class), mock(BlockClient.class), publisher,
                com.tiktok.chatservice.sticker.StickerCatalog.load("http://localhost:9000", "video-media"), prefs,
                mock(com.tiktok.chatservice.client.VideoClient.class),
                mock(com.tiktok.chatservice.client.StoryClient.class),
                mock(com.tiktok.chatservice.client.ShareStatsClient.class));
        when(conversationService.requireParticipant(any(), any())).thenAnswer(call ->
                conversations.findByIdAndDeletedAtIsNull(call.getArgument(1)).orElseThrow());
        when(conversationService.promoteIfMutual(any())).thenAnswer(call -> call.getArgument(0));
    }

    private Message put(String id, Instant sentAt, Long sender) {
        Message m = Message.builder().id(id).conversationId("c1").senderId(sender).type(MessageType.TEXT)
                .content("hi " + id).sentAt(sentAt).build();
        messages.save(m);
        conversations.recordMessage("c1", sender, MessageType.TEXT, m.getContent(), sentAt);
        return m;
    }

    private Conversation stored() {
        return conversations.findById("c1").orElseThrow();
    }

    // ---- the repository's mark

    @Test
    void aMarkOnlyMovesToALaterPosition_andRepeatingItLeavesTheTimeAlone() {
        assertThat(conversations.advanceMark("c1", B, MarkKind.READ, "m5", T0.plusSeconds(5), T0.plusSeconds(100))).isTrue();
        // Same position again, a replay long after: not an advance, and "seen at" stays put.
        assertThat(conversations.advanceMark("c1", B, MarkKind.READ, "m5", T0.plusSeconds(5), T0.plusSeconds(900))).isFalse();
        assertThat(conversations.advanceMark("c1", B, MarkKind.READ, "m3", T0.plusSeconds(3), T0.plusSeconds(901))).isFalse();

        assertThat(stored().getReadMark().get("2").messageId()).isEqualTo("m5");
        assertThat(stored().getReadMark().get("2").at()).isEqualTo(T0.plusSeconds(100));
    }

    @Test
    void messagesFromTheSameMillisecondAreOrderedById() {
        assertThat(conversations.advanceMark("c1", B, MarkKind.DELIVERED, "100", T0, T0.plusSeconds(1))).isTrue();
        assertThat(conversations.advanceMark("c1", B, MarkKind.DELIVERED, "100", T0, T0.plusSeconds(2))).isFalse();
        assertThat(conversations.advanceMark("c1", B, MarkKind.DELIVERED, "101", T0, T0.plusSeconds(3))).isTrue();
        assertThat(conversations.advanceMark("c1", B, MarkKind.DELIVERED, "099", T0, T0.plusSeconds(4))).isFalse();

        assertThat(stored().getDeliveredMark().get("2").messageId()).isEqualTo("101");
    }

    @Test
    void eachUserHasTheirOwnMarks_andReadingMovesUnreadBookkeepingToThatMessagesTime() {
        conversations.advanceMark("c1", B, MarkKind.READ, "m2", T0.plusSeconds(2), T0.plusSeconds(50));

        assertThat(stored().getReadMark()).containsOnlyKeys("2");
        assertThat(stored().getDeliveredMark()).isNullOrEmpty();
        assertThat(stored().getLastReadAt().get("2")).isEqualTo(T0.plusSeconds(2));
    }

    @Test
    void sendingIsNotReading() {
        put("m1", T0.plusSeconds(1), B);   // B's message, never read by A
        put("m2", T0.plusSeconds(2), A);   // A replies without opening the thread

        Conversation c = stored();
        assertThat(c.getLastSentAt()).containsKeys("1", "2");
        assertThat(c.getLastReadAt()).isNullOrEmpty();
        assertThat(c.getReadMark()).isNullOrEmpty();
    }

    @Test
    void concurrentAcknowledgementsLeaveTheFurthestPosition() throws Exception {
        List<Future<?>> all = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            for (int i = 1; i <= 40; i++) {
                int n = i;
                all.add(pool.submit(() -> {
                    go.await();
                    return conversations.advanceMark("c1", B, MarkKind.DELIVERED, String.format("m%03d", n),
                            T0.plusSeconds(n), T0.plusSeconds(1000 + n));
                }));
            }
            go.countDown();
            for (Future<?> f : all) {
                f.get(20, TimeUnit.SECONDS);
            }
        }
        assertThat(stored().getDeliveredMark().get("2").messageId()).isEqualTo("m040");
    }

    // ---- the service, end to end

    @Test
    void anAcknowledgementOfM1ThatArrivesAfterM2WasSentDoesNotCoverM2() {
        put("m1", T0.plusSeconds(1), A);
        put("m2", T0.plusSeconds(2), A);   // sent while B's acknowledgement of m1 was on its way

        ReceiptView own = service.markDelivered("c1", B, List.of("m1"));

        assertThat(own.delivered().messageId()).isEqualTo("m1");
        assertThat(stored().getDeliveredMark().get("2").messageId()).isEqualTo("m1");
        assertThat(stored().getReadMark()).isNullOrEmpty();
    }

    @Test
    void twoMessagesWithTheSameSentAtAreToldApart() {
        put("100", T0, A);
        put("101", T0, A);

        service.markDelivered("c1", B, List.of("100"));
        assertThat(stored().getDeliveredMark().get("2").messageId()).isEqualTo("100");

        service.markDelivered("c1", B, List.of("100", "101"));
        assertThat(stored().getDeliveredMark().get("2").messageId()).isEqualTo("101");
    }

    @Test
    void aGapIsNotCrossed_andFillingItLetsTheMarkThrough() {
        put("m1", T0.plusSeconds(1), A);
        put("m2", T0.plusSeconds(2), A);
        put("m3", T0.plusSeconds(3), A);
        service.markDelivered("c1", B, List.of("m1"));

        // m2 never reached this client: m3 alone must not carry the mark past it.
        service.markDelivered("c1", B, List.of("m3"));
        assertThat(stored().getDeliveredMark().get("2").messageId()).isEqualTo("m1");

        service.markDelivered("c1", B, List.of("m2", "m3"));
        assertThat(stored().getDeliveredMark().get("2").messageId()).isEqualTo("m3");
    }

    @Test
    void readingMovesBothMarks_leavesNewerMessagesUnread_andACorrectUnreadFlag() {
        put("m1", T0.plusSeconds(1), A);
        put("m2", T0.plusSeconds(2), A);

        service.markRead("c1", B, "m1");

        Conversation c = stored();
        assertThat(c.getReadMark().get("2").messageId()).isEqualTo("m1");
        assertThat(c.getDeliveredMark().get("2").messageId()).isEqualTo("m1");
        // m2 came after what B read, so the thread is still unread for B.
        assertThat(c.getLastSentAt().get("1")).isAfter(c.getLastReadAt().get("2"));
    }

    @Test
    void sendingAMessageDoesNotMarkWhatCameBeforeAsSeen() {
        put("m1", T0.plusSeconds(1), A);
        when(conversationService.requireParticipant(B, "c1")).thenAnswer(call -> stored());
        service.sendMessage("c1", B, new SendMessageRequest("a reply, without having opened the thread"));

        Conversation c = stored();
        assertThat(c.getReadMark()).isNullOrEmpty();
        assertThat(c.getDeliveredMark()).isNullOrEmpty();
    }
}
