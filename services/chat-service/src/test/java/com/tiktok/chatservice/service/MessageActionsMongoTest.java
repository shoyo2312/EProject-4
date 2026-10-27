package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.DmAudience;
import com.tiktok.chatservice.client.FriendshipClient;
import com.tiktok.chatservice.client.SettingsClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.dto.response.MessageUpdateResponse;
import com.tiktok.chatservice.dto.response.ReplyQuote;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.exception.InvalidReactionException;
import com.tiktok.chatservice.exception.InvalidReplyTargetException;
import com.tiktok.chatservice.exception.MessageNotFoundException;
import com.tiktok.chatservice.exception.MessageRecalledException;
import com.tiktok.chatservice.exception.NotMessageAuthorException;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Reactions, replies, recall and delete-for-me against a real MongoDB: what is stored, what each
 * participant is shown after a round trip, and that the conditional updates hold under a race.
 */
@DataMongoTest
@Testcontainers
class MessageActionsMongoTest {

    private static final long A = 1L;
    private static final long B = 2L;
    private static final long OUTSIDER = 3L;
    private static final String HEART = "❤️";
    private static final String THUMBS_UP = "👍";

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
                publisher, new ConversationViews(new ConversationMapperImpl(), messageRepository, prefsRepository));
        messageService = new MessageServiceImpl(conversationService, conversationRepository, messageRepository,
                new MessageMapperImpl(), mock(SimpMessagingTemplate.class), mock(BlockClient.class), publisher, catalog, prefsRepository);
        conversationRepository.save(Conversation.builder().id("c1").participantIds(List.of(A, B))
                .participantKey("1_2").initiatorId(A).build());
        conversationRepository.save(Conversation.builder().id("c2").participantIds(List.of(A, OUTSIDER))
                .participantKey("1_3").initiatorId(A).build());
    }

    private MessageResponse send(long from, String text) {
        return messageService.sendMessage("c1", from, new SendMessageRequest(text));
    }

    private MessageResponse find(long viewer, String id) {
        return messageService.listMessages("c1", viewer, null, 100).items().stream()
                .filter(m -> m.id().equals(id)).findFirst().orElse(null);
    }

    // ---- reactions

    @Test
    void reactionIsSetChangedAndRemovedAndRepeatedCallsChangeNothing() {
        MessageResponse m = send(A, "hello");

        MessageUpdateResponse first = messageService.react("c1", B, m.id(), HEART);
        MessageUpdateResponse again = messageService.react("c1", B, m.id(), HEART);
        assertThat(first.reactions()).hasSize(1);
        assertThat(again.revision()).isEqualTo(first.revision());

        MessageUpdateResponse changed = messageService.react("c1", B, m.id(), THUMBS_UP);
        assertThat(changed.reactions()).singleElement().satisfies(r -> assertThat(r.emoji()).isEqualTo(THUMBS_UP));
        assertThat(changed.revision()).isGreaterThan(first.revision());

        MessageUpdateResponse removed = messageService.unreact("c1", B, m.id());
        MessageUpdateResponse removedAgain = messageService.unreact("c1", B, m.id());
        assertThat(removed.reactions()).isEmpty();
        assertThat(removedAgain.revision()).isEqualTo(removed.revision());
    }

    @Test
    void bothParticipantsReactionsAreKeptWhenTheyReactAtTheSameTime() throws Exception {
        MessageResponse m = send(A, "hello");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> a = pool.submit(() -> { await(go); messageService.react("c1", A, m.id(), HEART); });
            Future<?> b = pool.submit(() -> { await(go); messageService.react("c1", B, m.id(), THUMBS_UP); });
            go.countDown();
            a.get();
            b.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(find(A, m.id()).reactions()).hasSize(2);
    }

    @Test
    void reactionsAreRefusedForOutsidersUnknownIdsOtherConversationsAndStrangeEmoji() {
        MessageResponse m = send(A, "hello");
        MessageResponse elsewhere = messageService.sendMessage("c2", A, new SendMessageRequest("other"));

        assertThatThrownBy(() -> messageService.react("c1", OUTSIDER, m.id(), HEART)).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> messageService.react("c1", B, "no-such-id", HEART)).isInstanceOf(MessageNotFoundException.class);
        assertThatThrownBy(() -> messageService.react("c1", B, elsewhere.id(), HEART)).isInstanceOf(MessageNotFoundException.class);
        assertThatThrownBy(() -> messageService.react("c1", B, m.id(), "x")).isInstanceOf(InvalidReactionException.class);
        assertThat(find(A, m.id()).reactions()).isEmpty();
    }

    @Test
    void aMessageDeletedForTheReactorCannotBeReactedTo() {
        MessageResponse m = send(A, "hello");
        messageService.hide("c1", B, m.id());

        assertThatThrownBy(() -> messageService.react("c1", B, m.id(), HEART)).isInstanceOf(MessageNotFoundException.class);
    }

    // ---- recall

    @Test
    void onlyTheAuthorRecallsAndTheContentAndReactionsLeaveStorage() {
        MessageResponse m = send(A, "secret");
        messageService.react("c1", B, m.id(), HEART);

        assertThatThrownBy(() -> messageService.recall("c1", B, m.id())).isInstanceOf(NotMessageAuthorException.class);
        MessageUpdateResponse update = messageService.recall("c1", A, m.id());

        assertThat(update.recalledAt()).isNotNull();
        assertThat(update.reactions()).isEmpty();
        Message stored = messageRepository.findById(m.id()).orElseThrow();
        assertThat(stored.getContent()).isNull();
        assertThat(stored.getReactions()).isNullOrEmpty();
        MessageResponse seenByB = find(B, m.id());
        assertThat(seenByB.content()).isNull();
        assertThat(seenByB.recalledAt()).isNotNull();

        long revision = update.revision();
        assertThat(messageService.recall("c1", A, m.id()).revision()).isEqualTo(revision);
    }

    @Test
    void aRecallDoesNotMoveLastMessageAtOrCountAsANewMessage() {
        MessageResponse m = send(A, "secret");
        Instant before = conversationRepository.findById("c1").orElseThrow().getLastMessageAt();

        messageService.recall("c1", A, m.id());

        ConversationResponse view = conversationService.getById(B, "c1");
        assertThat(view.lastMessageAt()).isEqualTo(before);
        assertThat(view.lastMessageRecalled()).isTrue();
        assertThat(view.lastMessageContent()).isNull();
    }

    @Test
    void recalledMessagesAreRefusedAsReactionAndReplyTargets() {
        MessageResponse m = send(A, "secret");
        messageService.recall("c1", A, m.id());

        assertThatThrownBy(() -> messageService.react("c1", B, m.id(), HEART)).isInstanceOf(MessageRecalledException.class);
        assertThatThrownBy(() -> messageService.sendMessage("c1", B, new SendMessageRequest("re").replyingTo(m.id())))
                .isInstanceOf(InvalidReplyTargetException.class);
    }

    @Test
    void aRecallRacingAReactionNeverLeavesContentOrReactionsBehind() throws Exception {
        for (int round = 0; round < 15; round++) {
            MessageResponse m = send(A, "secret " + round);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);
            try {
                Future<?> recall = pool.submit(() -> { await(go); messageService.recall("c1", A, m.id()); });
                Future<?> react = pool.submit(() -> {
                    await(go);
                    try {
                        messageService.react("c1", B, m.id(), HEART);
                    } catch (MessageRecalledException expected) {
                        // The recall won the race.
                    }
                });
                go.countDown();
                recall.get();
                react.get();
            } finally {
                pool.shutdownNow();
            }
            Message stored = messageRepository.findById(m.id()).orElseThrow();
            assertThat(stored.isRecalled()).isTrue();
            assertThat(stored.getContent()).isNull();
            assertThat(stored.getReactions()).isNullOrEmpty();
        }
    }

    // ---- replies

    @Test
    void aReplyQuotesAnOriginalThatIsNotInTheLoadedPage() {
        MessageResponse original = send(B, "the original");
        for (int i = 0; i < 5; i++) {
            send(A, "filler " + i);
        }
        MessageResponse reply = messageService.sendMessage("c1", A, new SendMessageRequest("answer").replyingTo(original.id()));

        assertThat(reply.replyTo().state()).isEqualTo(ReplyQuote.State.OK);
        // A page of two holds the reply but not its original.
        MessagePageResponse page = messageService.listMessages("c1", A, null, 2);
        assertThat(page.items().get(0).id()).isEqualTo(reply.id());
        assertThat(page.items().get(0).replyTo().content()).isEqualTo("the original");
    }

    @Test
    void aStickerCanReplyToo() {
        MessageResponse original = send(B, "look");
        MessageResponse reply = messageService.sendMessage("c1", A, SendMessageRequest.sticker("heart-eyes").replyingTo(original.id()));

        assertThat(reply.replyToMessageId()).isEqualTo(original.id());
        assertThat(reply.sticker()).isNotNull();
    }

    @Test
    void aBadReplyTargetStoresNothingAndLeavesThePreviewAlone() {
        MessageResponse elsewhere = messageService.sendMessage("c2", A, new SendMessageRequest("other"));
        MessageResponse hiddenByA = send(B, "hidden by A");
        messageService.hide("c1", A, hiddenByA.id());
        send(B, "last");
        long stored = messageRepository.count();
        Instant lastAt = conversationRepository.findById("c1").orElseThrow().getLastMessageAt();

        for (String target : new String[]{"missing", elsewhere.id(), hiddenByA.id(), " "}) {
            assertThatThrownBy(() -> messageService.sendMessage("c1", A, new SendMessageRequest("x").replyingTo(target)))
                    .isInstanceOf(InvalidReplyTargetException.class);
        }

        assertThat(messageRepository.count()).isEqualTo(stored);
        assertThat(conversationRepository.findById("c1").orElseThrow().getLastMessageAt()).isEqualTo(lastAt);
    }

    @Test
    void recallingTheOriginalBlanksTheQuoteForEveryone() {
        MessageResponse original = send(A, "take this back");
        MessageResponse reply = messageService.sendMessage("c1", B, new SendMessageRequest("ok").replyingTo(original.id()));

        messageService.recall("c1", A, original.id());

        for (long viewer : new long[]{A, B}) {
            ReplyQuote quote = find(viewer, reply.id()).replyTo();
            assertThat(quote.state()).isEqualTo(ReplyQuote.State.RECALLED);
            assertThat(quote.content()).isNull();
        }
    }

    // ---- delete for me

    @Test
    void deletingForMeHidesItFromMeAndOnlyMe() {
        MessageResponse m = send(A, "hello");

        messageService.hide("c1", A, m.id());

        assertThat(find(A, m.id())).isNull();
        assertThat(find(B, m.id())).isNotNull();
        assertThat(messageService.exportMessages("c1", A, null, 100).items()).isEmpty();
        assertThat(messageService.exportMessages("c1", B, null, 100).items()).hasSize(1);
        // The author can still recall what they deleted from their own view.
        assertThat(messageService.recall("c1", A, m.id()).recalledAt()).isNotNull();
        assertThat(find(B, m.id()).recalledAt()).isNotNull();
    }

    @Test
    void bothSidesCanDeleteIndependently() {
        MessageResponse m = send(A, "hello");

        messageService.hide("c1", A, m.id());
        messageService.hide("c1", B, m.id());
        messageService.hide("c1", B, m.id());

        assertThat(find(A, m.id())).isNull();
        assertThat(find(B, m.id())).isNull();
        assertThat(messageRepository.findById(m.id())).isPresent();
    }

    @Test
    void aQuoteOfAMessageIDeletedIsUnavailableToMeButNotToThem() {
        MessageResponse original = send(B, "original");
        MessageResponse reply = messageService.sendMessage("c1", A, new SendMessageRequest("re").replyingTo(original.id()));

        messageService.hide("c1", A, original.id());

        assertThat(find(A, reply.id()).replyTo().state()).isEqualTo(ReplyQuote.State.UNAVAILABLE);
        assertThat(find(A, reply.id()).replyTo().content()).isNull();
        assertThat(find(B, reply.id()).replyTo().state()).isEqualTo(ReplyQuote.State.OK);
    }

    @Test
    void deletingNewestForMeMovesMyPreviewBackWithoutTouchingTheirs() {
        send(A, "first");
        MessageResponse newest = send(A, "second");

        messageService.hide("c1", B, newest.id());

        assertThat(conversationService.getById(B, "c1").lastMessageContent()).isEqualTo("first");
        assertThat(conversationService.getById(A, "c1").lastMessageContent()).isEqualTo("second");
    }

    @Test
    void pagingStaysCorrectWhenManyMessagesAreHidden() {
        String[] ids = new String[12];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = send(A, "m" + i).id();
        }
        // Hide every message but the oldest two and the newest one.
        for (int i = 2; i < ids.length - 1; i++) {
            messageService.hide("c1", B, ids[i]);
        }

        MessagePageResponse first = messageService.listMessages("c1", B, null, 2);
        assertThat(first.items()).extracting(MessageResponse::content).containsExactly("m11", "m1");
        assertThat(first.hasMore()).isTrue();
        MessagePageResponse second = messageService.listMessages("c1", B, first.nextCursor(), 2);
        assertThat(second.items()).extracting(MessageResponse::content).containsExactly("m0");
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void recallAndHideNeverHandAnotherRequestTurnOrChangeTheRequestMessage() {
        // A request conversation: A opened it, and has used its one message.
        conversationRepository.save(Conversation.builder().id("r1").participantIds(List.of(A, 9L))
                .participantKey("1_9").initiatorId(A).requestState(com.tiktok.chatservice.entity.MessageRequestState.PENDING)
                .build());
        MessageResponse first = messageService.sendMessage("r1", A, new SendMessageRequest("please accept"));

        messageService.hide("r1", A, first.id());
        messageService.recall("r1", A, first.id());

        assertThatThrownBy(() -> messageService.sendMessage("r1", A, new SendMessageRequest("again")))
                .isInstanceOf(com.tiktok.chatservice.exception.MessageRequestLimitReachedException.class);
        assertThat(messageRepository.findById(first.id()).orElseThrow().getRequestMessage()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
