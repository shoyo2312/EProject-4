package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.DmAudience;
import com.tiktok.chatservice.client.FriendshipClient;
import com.tiktok.chatservice.client.SettingsClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.exception.MessageRequestNotAcceptedException;
import com.tiktok.chatservice.mapper.ConversationMapperImpl;
import com.tiktok.chatservice.mapper.MessageMapperImpl;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import com.tiktok.chatservice.websocket.ChatWebSocketController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import java.security.Principal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one-message limit and the Accept/Delete transitions against a real MongoDB, because what they
 * promise is a property of the database (a unique partial index, conditional updates) and a mock
 * would only repeat what the code claims. This Mongo is a standalone: no transactions anywhere here.
 *
 * <p>A = 1 opens the request, B = 2 receives it.
 */
@DataMongoTest
@Testcontainers
class MessageRequestConcurrencyTest {

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

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final FriendshipClient friendshipClient = mock(FriendshipClient.class);
    private final ChatEventPublisher eventPublisher = mock(ChatEventPublisher.class);

    private ConversationServiceImpl conversationService;
    private MessageServiceImpl messageService;
    private ChatWebSocketController stomp;

    @BeforeEach
    void setUp() {
        conversationRepository.deleteAll();
        messageRepository.deleteAll();
        SettingsClient settingsClient = mock(SettingsClient.class);
        when(settingsClient.dmAudience(any())).thenReturn(DmAudience.EVERYONE);
        when(friendshipClient.areFriends(any(), any())).thenReturn(false);
        conversationService = new ConversationServiceImpl(conversationRepository, mock(BlockClient.class), settingsClient,
                friendshipClient, eventPublisher, new ConversationViews(new ConversationMapperImpl(), messageRepository, prefsRepository, mock(BlockClient.class)));
        messageService = serviceOver(messageRepository);
        stomp = new ChatWebSocketController(messageService, null);
    }

    private MessageServiceImpl serviceOver(MessageRepository messages) {
        return new MessageServiceImpl(conversationService, conversationRepository, messages,
                new MessageMapperImpl(), messagingTemplate, mock(BlockClient.class), eventPublisher, com.tiktok.chatservice.sticker.StickerCatalog.load("http://localhost:9000", "video-media"), prefsRepository,
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.VideoClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.StoryClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.ShareStatsClient.class), mock(com.tiktok.chatservice.repository.ChatImageRepository.class), mock(com.tiktok.chatservice.service.ChatImageService.class));
    }

    private Conversation pendingRequest() {
        return conversationRepository.save(Conversation.builder().id("c1").participantIds(List.of(A, B))
                .participantKey("1_2").initiatorId(A).requestState(MessageRequestState.PENDING).build());
    }

    private List<Message> flagged() {
        return messageRepository.findAll().stream().filter(m -> Boolean.TRUE.equals(m.getRequestMessage())).toList();
    }

    /** Runs every task at the same instant and returns what each threw, or null for a success. */
    private List<Throwable> inParallel(List<Callable<Object>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Throwable>> futures = new ArrayList<>();
        for (Callable<Object> task : tasks) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    task.call();
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
        return outcomes;
    }

    private static Principal principal(long user) {
        return () -> String.valueOf(user);
    }

    // --- the limit ----------------------------------------------------------------------------

    @Test
    void twentyConcurrentSendsFromTheInitiatorStoreExactlyOne() throws Exception {
        pendingRequest();
        List<Callable<Object>> sends = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            int n = i;
            sends.add(() -> messageService.sendMessage("c1", A, new SendMessageRequest("hello " + n)));
        }

        List<Throwable> outcomes = inParallel(sends);

        assertThat(outcomes.stream().filter(t -> t == null)).hasSize(1);
        assertThat(outcomes.stream().filter(t -> t != null))
                .allSatisfy(t -> assertThat(t).isInstanceOf(MessageRequestLimitReachedException.class));
        assertThat(messageRepository.findAll()).hasSize(1);
        assertThat(flagged()).hasSize(1);
        // Broadcast once: a refused send must not leak to the recipient.
        verify(messagingTemplate, times(1)).convertAndSend(org.mockito.ArgumentMatchers.eq("/topic/conversations/c1"),
                any(Object.class));
    }

    /** REST and STOMP are two doors into the same method; together they still share one allowance. */
    @Test
    void restAndStompSendingAtOnceStillStoreExactlyOne() throws Exception {
        pendingRequest();
        List<Callable<Object>> sends = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            sends.add(() -> messageService.sendMessage("c1", A, new SendMessageRequest("rest " + n)));
            sends.add(() -> {
                stomp.send("c1", new SendMessageRequest("stomp " + n), principal(A));
                return null;
            });
        }

        List<Throwable> outcomes = inParallel(sends);

        assertThat(outcomes.stream().filter(t -> t == null)).hasSize(1);
        assertThat(messageRepository.findAll()).hasSize(1);
    }

    @Test
    void whenBothSidesTryTheFirstMessageTheRecipientIsAlwaysRefused() throws Exception {
        pendingRequest();
        List<Callable<Object>> sends = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            sends.add(() -> messageService.sendMessage("c1", A, new SendMessageRequest("from A")));
            sends.add(() -> messageService.sendMessage("c1", B, new SendMessageRequest("from B")));
        }

        inParallel(sends);

        List<Message> stored = messageRepository.findAll();
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getSenderId()).isEqualTo(A);
    }

    @Test
    void theRecipientCannotSendBeforeAcceptingAndCanAfterwards() {
        pendingRequest();
        messageService.sendMessage("c1", A, new SendMessageRequest("hello"));

        assertThatThrownBy(() -> messageService.sendMessage("c1", B, new SendMessageRequest("hi")))
                .isInstanceOf(MessageRequestNotAcceptedException.class);
        assertThatThrownBy(() -> messageService.sendMessage("c1", A, new SendMessageRequest("hello?")))
                .isInstanceOf(MessageRequestLimitReachedException.class);

        conversationService.acceptRequest(B, "c1");
        messageService.sendMessage("c1", B, new SendMessageRequest("hi"));
        messageService.sendMessage("c1", A, new SendMessageRequest("hey"));

        assertThat(messageRepository.findAll()).hasSize(3);
        assertThat(flagged()).hasSize(1);
    }

    /** Whatever order Accept and the first send land in, one flagged message at most and an ordinary thread after. */
    @Test
    void acceptRacingTheFirstSendNeverStoresMoreThanOneFlaggedMessage() throws Exception {
        for (int round = 0; round < 50; round++) {
            conversationRepository.deleteAll();
            messageRepository.deleteAll();
            pendingRequest();

            List<Throwable> outcomes = inParallel(List.of(
                    () -> messageService.sendMessage("c1", A, new SendMessageRequest("first")),
                    () -> messageService.sendMessage("c1", A, new SendMessageRequest("second")),
                    () -> conversationService.acceptRequest(B, "c1")));

            assertThat(flagged().size()).isLessThanOrEqualTo(1);
            assertThat(messageRepository.findAll().size()).isLessThanOrEqualTo(2);
            assertThat(outcomes.stream().filter(t -> t != null))
                    .allSatisfy(t -> assertThat(t).isInstanceOf(MessageRequestLimitReachedException.class));
            assertThat(conversationRepository.findById("c1").orElseThrow().getRequestState()).isNull();
        }
    }

    // --- Accept / Delete ----------------------------------------------------------------------

    @Test
    void acceptAndDeleteRacingLeaveExactlyOneWinner() throws Exception {
        for (int round = 0; round < 50; round++) {
            conversationRepository.deleteAll();
            pendingRequest();
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger declined = new AtomicInteger();

            inParallel(List.of(
                    () -> {
                        if (conversationRepository.acceptRequest("c1", B, Instant.now())) {
                            accepted.incrementAndGet();
                        }
                        return null;
                    },
                    () -> {
                        if (conversationRepository.declineRequest("c1", B, Instant.now())) {
                            declined.incrementAndGet();
                        }
                        return null;
                    }));

            assertThat(accepted.get() + declined.get()).isEqualTo(1);
            MessageRequestState state = conversationRepository.findById("c1").orElseThrow().getRequestState();
            assertThat(state).isEqualTo(accepted.get() == 1 ? null : MessageRequestState.DECLINED);
        }
    }

    @Test
    void onlyTheRecipientCanChangeTheState_andOnlyOnce() {
        pendingRequest();

        assertThat(conversationRepository.acceptRequest("c1", A, Instant.now())).isFalse();
        assertThat(conversationRepository.declineRequest("c1", 99L, Instant.now())).isFalse();
        assertThat(conversationRepository.declineRequest("c1", B, Instant.now())).isTrue();
        assertThat(conversationRepository.declineRequest("c1", B, Instant.now())).isFalse();
        assertThat(conversationRepository.acceptRequest("c1", B, Instant.now())).isFalse();
    }

    @Test
    void deletingKeepsTheConversationAndItsMessages() {
        pendingRequest();
        messageService.sendMessage("c1", A, new SendMessageRequest("hello"));

        conversationService.declineRequest(B, "c1");

        Conversation kept = conversationRepository.findById("c1").orElseThrow();
        assertThat(kept.getRequestState()).isEqualTo(MessageRequestState.DECLINED);
        assertThat(kept.getDeletedAt()).isNull();
        assertThat(messageRepository.findAll()).hasSize(1);
        // And the initiator cannot get a second go through the same pair.
        assertThatThrownBy(() -> messageService.sendMessage("c1", A, new SendMessageRequest("please?")))
                .isInstanceOf(MessageRequestLimitReachedException.class);
    }

    // --- failure ------------------------------------------------------------------------------

    /** The allowance is the stored row, so a save that fails spends nothing. */
    @Test
    void aFirstSaveThatFailsDoesNotUseUpTheAllowance() {
        pendingRequest();
        MessageRepository flaky = mock(MessageRepository.class);
        doAnswer(invocation -> {
            throw new IllegalStateException("mongo hiccup");
        }).doAnswer(invocation -> messageRepository.save(invocation.<Message>getArgument(0)))
                .when(flaky).save(any(Message.class));
        MessageServiceImpl flakyService = serviceOver(flaky);

        assertThatThrownBy(() -> flakyService.sendMessage("c1", A, new SendMessageRequest("try 1")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(messageRepository.findAll()).isEmpty();

        flakyService.sendMessage("c1", A, new SendMessageRequest("try 2"));

        assertThat(messageRepository.findAll()).hasSize(1);
        assertThat(flagged()).hasSize(1);
    }

    // --- the index and the queries ------------------------------------------------------------

    @Test
    void theUniquePartialIndexRefusesASecondFlaggedMessageButNotOrdinaryOnes() {
        messageRepository.save(message("m1", true));
        assertThatThrownBy(() -> messageRepository.save(message("m2", true))).isInstanceOf(DuplicateKeyException.class);
        for (int i = 0; i < 5; i++) {
            messageRepository.save(message("ordinary" + i, null));
        }
        messageRepository.save(message("other-thread", true, "c2"));

        assertThat(messageRepository.findAll()).hasSize(7);
    }

    private static Message message(String id, Boolean flagged) {
        return message(id, flagged, "c1");
    }

    private static Message message(String id, Boolean flagged, String conversationId) {
        return Message.builder().id(id).conversationId(conversationId).senderId(A).content("x")
                .sentAt(Instant.now()).requestMessage(flagged).build();
    }

    @Test
    void theInboxAndTheRequestBoxPartitionConversationsPerViewer() {
        Instant t = Instant.parse("2026-10-26T12:00:00Z");
        // legacy: no state, no initiator
        conversationRepository.save(Conversation.builder().id("legacy").participantIds(List.of(A, B))
                .participantKey("1_2").lastMessageAt(t).build());
        // B has a request from 10, and A opened one to 20 (still pending, with a message)
        conversationRepository.save(Conversation.builder().id("toB").participantIds(List.of(10L, B))
                .participantKey("2_10").initiatorId(10L).requestState(MessageRequestState.PENDING)
                .lastMessageAt(t.plusSeconds(1)).build());
        conversationRepository.save(Conversation.builder().id("fromA").participantIds(List.of(A, 20L))
                .participantKey("1_20").initiatorId(A).requestState(MessageRequestState.PENDING)
                .lastMessageAt(t.plusSeconds(2)).build());
        // an empty request opened by 30 to B: not a request yet
        conversationRepository.save(Conversation.builder().id("empty").participantIds(List.of(30L, B))
                .participantKey("2_30").initiatorId(30L).requestState(MessageRequestState.PENDING).build());
        // B deleted one from 40
        conversationRepository.save(Conversation.builder().id("deleted").participantIds(List.of(40L, B))
                .participantKey("2_40").initiatorId(40L).requestState(MessageRequestState.DECLINED)
                .lastMessageAt(t.plusSeconds(3)).build());

        assertThat(conversationRepository.findInbox(B)).extracting(Conversation::getId).containsExactly("legacy");
        assertThat(conversationRepository.findIncomingRequests(B)).extracting(Conversation::getId)
                .containsExactly("toB");
        // The opener sees their own request in their inbox, deleted or not.
        assertThat(conversationRepository.findInbox(A)).extracting(Conversation::getId)
                .containsExactlyInAnyOrder("legacy", "fromA");
        assertThat(conversationRepository.findInbox(40L)).extracting(Conversation::getId).containsExactly("deleted");
        assertThat(conversationRepository.findIncomingRequests(A)).isEmpty();
    }
    // --- fifteen-day retry and reopening ------------------------------------------------------

    private Conversation requestSentDaysAgo(MessageRequestState state, int days) {
        Instant sentAt = Instant.now().minus(java.time.Duration.ofDays(days));
        messageRepository.save(Message.builder().id("old").conversationId("c1").senderId(A).content("first")
                .sentAt(sentAt).requestMessage(true).build());
        return conversationRepository.save(Conversation.builder().id("c1").participantIds(List.of(A, B))
                .participantKey("1_2").initiatorId(A).requestState(state).lastMessageAt(sentAt)
                .lastMessageContent("first").lastMessageSenderId(A).build());
    }

    @Test
    void beforeFifteenDaysAnotherRequestMessageIsRefusedAndNothingChanges() {
        requestSentDaysAgo(MessageRequestState.PENDING, 14);

        assertThatThrownBy(() -> messageService.sendMessage("c1", A, new SendMessageRequest("any news?")))
                .isInstanceOf(MessageRequestLimitReachedException.class);

        assertThat(messageRepository.findAll()).hasSize(1);
        assertThat(flagged()).extracting(Message::getId).containsExactly("old");
    }

    /** The finished round stops holding the unique slot; racing retries still store exactly one new message. */
    @Test
    void afterFifteenDaysRacingRetriesStoreExactlyOneNewRoundMessage() throws Exception {
        requestSentDaysAgo(MessageRequestState.PENDING, 16);
        List<Callable<Object>> sends = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int n = i;
            sends.add(() -> messageService.sendMessage("c1", A, new SendMessageRequest("retry " + n)));
        }

        List<Throwable> outcomes = inParallel(sends);

        assertThat(outcomes.stream().filter(t -> t == null)).hasSize(1);
        assertThat(outcomes.stream().filter(t -> t != null))
                .allSatisfy(t -> assertThat(t).isInstanceOf(MessageRequestLimitReachedException.class));
        assertThat(messageRepository.findAll()).hasSize(2);
        assertThat(flagged()).hasSize(1);
        assertThat(flagged().get(0).getId()).isNotEqualTo("old");
        assertThat(messageRepository.findAll().stream().filter(m -> m.getId().equals("old")).findFirst().orElseThrow()
                .getRequestMessage()).isNull();
    }

    @Test
    void aRetryOnADeletedRequestPutsItBackInTheRecipientsBox() {
        requestSentDaysAgo(MessageRequestState.DECLINED, 20);
        assertThat(conversationRepository.findIncomingRequests(B)).isEmpty();

        messageService.sendMessage("c1", A, new SendMessageRequest("hello again"));

        assertThat(conversationRepository.findIncomingRequests(B)).extracting(Conversation::getId).containsExactly("c1");
        assertThat(conversationRepository.findById("c1").orElseThrow().getRequestState())
                .isEqualTo(MessageRequestState.PENDING);
        // and the round is spent again
        assertThatThrownBy(() -> messageService.sendMessage("c1", A, new SendMessageRequest("and again")))
                .isInstanceOf(MessageRequestLimitReachedException.class);
    }

    /** Opening does nothing; sending does. Two sends at once still reopen once and store both. */
    @Test
    void theDeciderSendingReopensTheThreadSoTheInitiatorCanReply() throws Exception {
        requestSentDaysAgo(MessageRequestState.DECLINED, 1);
        assertThatThrownBy(() -> messageService.sendMessage("c1", A, new SendMessageRequest("please")))
                .isInstanceOf(MessageRequestLimitReachedException.class);

        List<Throwable> outcomes = inParallel(List.of(
                () -> messageService.sendMessage("c1", B, new SendMessageRequest("hi, I changed my mind")),
                () -> messageService.sendMessage("c1", B, new SendMessageRequest("hello?"))));

        assertThat(outcomes).containsOnlyNulls();
        assertThat(conversationRepository.findById("c1").orElseThrow().getRequestState()).isNull();
        messageService.sendMessage("c1", A, new SendMessageRequest("thanks for writing"));
        assertThat(messageRepository.findAll()).hasSize(4);
        assertThat(flagged()).extracting(Message::getId).containsExactly("old");
    }
}
