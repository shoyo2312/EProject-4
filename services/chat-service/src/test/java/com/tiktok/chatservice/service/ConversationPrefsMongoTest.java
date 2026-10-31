package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.DmAudience;
import com.tiktok.chatservice.client.FriendshipClient;
import com.tiktok.chatservice.client.SettingsClient;
import com.tiktok.chatservice.dto.request.MuteRequest;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.dto.response.ReplyQuote;
import com.tiktok.chatservice.entity.ChatUserPrefs;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.entity.MuteMode;
import com.tiktok.chatservice.exception.MessageNotFoundException;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.exception.PinLimitReachedException;
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

import java.time.Duration;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Pin, mute and delete-for-me against a real MongoDB: what is stored per person, and what each side then sees. */
@DataMongoTest
@Testcontainers
class ConversationPrefsMongoTest {

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
    private ConversationPrefsService prefsService;
    private MessageServiceImpl messageService;
    private BlockClient blockClient;

    @BeforeEach
    void setUp() {
        conversationRepository.deleteAll();
        messageRepository.deleteAll();
        prefsRepository.deleteAll();
        SettingsClient settings = mock(SettingsClient.class);
        when(settings.dmAudience(any())).thenReturn(DmAudience.EVERYONE);
        FriendshipClient friends = mock(FriendshipClient.class);
        when(friends.areFriends(any(), any())).thenReturn(false);
        blockClient = mock(BlockClient.class);
        ChatEventPublisher publisher = mock(ChatEventPublisher.class);
        ConversationViews views = new ConversationViews(new ConversationMapperImpl(), messageRepository, prefsRepository, blockClient);
        conversationService = new ConversationServiceImpl(conversationRepository, blockClient, settings, friends, publisher, views);
        prefsService = new ConversationPrefsService(conversationService, prefsRepository, conversationRepository, publisher, views);
        messageService = new MessageServiceImpl(conversationService, conversationRepository, messageRepository,
                new MessageMapperImpl(), mock(SimpMessagingTemplate.class), blockClient, publisher, catalog, prefsRepository,
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.VideoClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.StoryClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.ShareStatsClient.class), mock(com.tiktok.chatservice.repository.ChatImageRepository.class), mock(com.tiktok.chatservice.service.ChatImageService.class));
    }

    /** Conversation {@code id} between A and a different partner each time. */
    private void conversation(String id, long partner) {
        conversationRepository.save(Conversation.builder().id(id).participantIds(List.of(A, partner))
                .participantKey(Math.min(A, partner) + "_" + Math.max(A, partner)).initiatorId(A).build());
    }

    private List<String> inboxIds(long viewer) {
        return conversationService.listForUser(viewer).stream().map(ConversationResponse::id).toList();
    }

    // ---- pin

    @Test
    void pinnedChatsComeFirstLatestPinOnTopAndUnpinningThenPinningAgainPutsItOnTop() throws Exception {
        for (int i = 1; i <= 4; i++) {
            conversation("c" + i, 10 + i);
        }
        messageService.sendMessage("c4", A, new SendMessageRequest("newest activity"));

        prefsService.pin(A, "c1");
        prefsService.pin(A, "c2");
        prefsService.pin(A, "c3");
        assertThat(inboxIds(A)).containsExactly("c3", "c2", "c1", "c4");

        prefsService.unpin(A, "c2");
        prefsService.pin(A, "c2");
        assertThat(inboxIds(A)).containsExactly("c2", "c3", "c1", "c4");

        // A new message in a pinned chat does not move it.
        messageService.sendMessage("c1", A, new SendMessageRequest("hello"));
        assertThat(inboxIds(A)).containsExactly("c2", "c3", "c1", "c4");
    }

    @Test
    void aFourthPinIsRefusedAndNothingIsUnpinnedForYou() {
        for (int i = 1; i <= 4; i++) {
            conversation("c" + i, 10 + i);
        }
        prefsService.pin(A, "c1");
        prefsService.pin(A, "c2");
        prefsService.pin(A, "c3");

        assertThatThrownBy(() -> prefsService.pin(A, "c4")).isInstanceOf(PinLimitReachedException.class);

        assertThat(prefsRepository.findById(A).orElseThrow().getPins()).hasSize(3);
        assertThat(prefsService.pin(A, "c3").pinnedAt()).isNotNull(); // already pinned: fine, even when full
    }

    @Test
    void pinningTheSameChatTwiceKeepsOneSlotAndItsPlace() {
        conversation("c1", 11);
        conversation("c2", 12);
        prefsService.pin(A, "c1");
        prefsService.pin(A, "c2");
        Instant before = prefsRepository.findById(A).orElseThrow().pinnedAt("c1");

        prefsService.pin(A, "c1");

        ChatUserPrefs after = prefsRepository.findById(A).orElseThrow();
        assertThat(after.getPins()).hasSize(2);
        assertThat(after.pinnedAt("c1")).isEqualTo(before);
    }

    @Test
    void parallelPinsFromManyDevicesNeverExceedThree() throws Exception {
        int chats = 10;
        for (int i = 1; i <= chats; i++) {
            conversation("c" + i, 10 + i);
        }
        ExecutorService pool = Executors.newFixedThreadPool(chats);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger refused = new AtomicInteger();
        List<Future<?>> running = new ArrayList<>();
        try {
            for (int i = 1; i <= chats; i++) {
                String id = "c" + i;
                running.add(pool.submit((Callable<Void>) () -> {
                    go.await();
                    try {
                        prefsService.pin(A, id);
                    } catch (PinLimitReachedException e) {
                        refused.incrementAndGet();
                    }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : running) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(prefsRepository.findById(A).orElseThrow().getPins()).hasSize(3);
        assertThat(refused.get()).isEqualTo(chats - 3);
    }

    @Test
    void pinsBelongToTheirOwnerAlone() {
        conversation("c1", B);
        prefsService.pin(A, "c1");

        assertThat(conversationService.getById(A, "c1").pinnedAt()).isNotNull();
        assertThat(conversationService.getById(B, "c1").pinnedAt()).isNull();
    }

    @Test
    void anOutsiderCannotPinMuteOrDeleteAndNothingIsWritten() {
        conversation("c1", B);

        assertThatThrownBy(() -> prefsService.pin(99L, "c1")).isInstanceOf(NotConversationParticipantException.class);
        assertThatThrownBy(() -> prefsService.mute(99L, "c1", MuteRequest.Length.INDEFINITE))
                .isInstanceOf(NotConversationParticipantException.class);
        assertThatThrownBy(() -> prefsService.deleteForMe(99L, "c1")).isInstanceOf(NotConversationParticipantException.class);
        assertThat(prefsRepository.count()).isZero();
    }

    // ---- mute

    @Test
    void theFourLengthsSetTheirEndFromTheServersClock() {
        conversation("c1", B);
        Instant now = Instant.now();

        ConversationResponse hour = prefsService.mute(A, "c1", MuteRequest.Length.ONE_HOUR);
        assertThat(hour.muteMode()).isEqualTo(MuteMode.TIMED);
        assertThat(hour.mutedUntil()).isBetween(now.plus(Duration.ofMinutes(59)), now.plus(Duration.ofMinutes(61)));

        assertThat(prefsService.mute(A, "c1", MuteRequest.Length.EIGHT_HOURS).mutedUntil())
                .isBetween(now.plus(Duration.ofHours(8).minusMinutes(1)), now.plus(Duration.ofHours(8).plusMinutes(1)));
        assertThat(prefsService.mute(A, "c1", MuteRequest.Length.TWENTY_FOUR_HOURS).mutedUntil())
                .isBetween(now.plus(Duration.ofHours(24).minusMinutes(1)), now.plus(Duration.ofHours(24).plusMinutes(1)));

        ConversationResponse forever = prefsService.mute(A, "c1", MuteRequest.Length.INDEFINITE);
        assertThat(forever.muteMode()).isEqualTo(MuteMode.INDEFINITE);
        assertThat(forever.mutedUntil()).isNull();
    }

    @Test
    void muteIsThePersonsOwnAndUnmuteClearsItAtOnce() {
        conversation("c1", B);
        prefsService.mute(A, "c1", MuteRequest.Length.INDEFINITE);

        assertThat(conversationService.getById(A, "c1").muteMode()).isEqualTo(MuteMode.INDEFINITE);
        assertThat(conversationService.getById(B, "c1").muteMode()).isEqualTo(MuteMode.NONE);

        assertThat(prefsService.unmute(A, "c1").muteMode()).isEqualTo(MuteMode.NONE);
        assertThat(prefsService.unmute(A, "c1").muteMode()).isEqualTo(MuteMode.NONE);
    }

    @Test
    void aTimedMuteEndsOnItsOwnWithNoJobRunning() {
        Instant end = Instant.parse("2026-10-27T10:00:00Z");
        ChatUserPrefs prefs = ChatUserPrefs.builder().id(A)
                .mutes(java.util.Map.of("c1", new ChatUserPrefs.Mute(MuteMode.TIMED, end))).build();

        assertThat(prefs.muteModeAt("c1", end.minusSeconds(1))).isEqualTo(MuteMode.TIMED);
        assertThat(prefs.mutedUntilAt("c1", end.minusSeconds(1))).isEqualTo(end);
        assertThat(prefs.muteModeAt("c1", end)).isEqualTo(MuteMode.NONE);
        assertThat(prefs.muteModeAt("c1", end.plus(Duration.ofDays(400)))).isEqualTo(MuteMode.NONE);
        assertThat(prefs.mutedUntilAt("c1", end.plusSeconds(1))).isNull();
        assertThat(prefs.muteModeAt("never-muted", end)).isEqualTo(MuteMode.NONE);
    }

    @Test
    void aMutedConversationStillReceivesAndCountsMessages() {
        conversation("c1", B);
        prefsService.mute(A, "c1", MuteRequest.Length.INDEFINITE);

        messageService.sendMessage("c1", B, new SendMessageRequest("you there?"));

        ConversationResponse view = conversationService.getById(A, "c1");
        assertThat(view.lastMessageContent()).isEqualTo("you there?");
        assertThat(view.hasUnread()).isTrue();
        assertThat(messageService.listMessages("c1", A, null, 20).items()).hasSize(1);
    }

    // ---- delete for me

    @Test
    void deletingHidesTheHistoryFromMeAndOnlyMeAndLeavesTheInbox() {
        conversation("c1", B);
        messageService.sendMessage("c1", A, new SendMessageRequest("one"));
        messageService.sendMessage("c1", B, new SendMessageRequest("two"));

        prefsService.deleteForMe(A, "c1");

        assertThat(inboxIds(A)).isEmpty();
        assertThat(messageService.listMessages("c1", A, null, 20).items()).isEmpty();
        assertThat(messageService.exportMessages("c1", A, null, 20).items()).isEmpty();
        assertThat(inboxIds(B)).containsExactly("c1");
        assertThat(messageService.listMessages("c1", B, null, 20).items()).hasSize(2);
        assertThat(conversationService.getById(A, "c1").inboxHidden()).isTrue();
        assertThat(conversationService.getById(A, "c1").lastMessageContent()).isNull();
        assertThat(conversationService.getById(B, "c1").lastMessageContent()).isEqualTo("two");
    }

    @Test
    void aNewMessageBringsTheChatBackWithoutTheOldHistory() throws Exception {
        conversation("c1", B);
        messageService.sendMessage("c1", B, new SendMessageRequest("before"));
        prefsService.deleteForMe(A, "c1");
        Thread.sleep(5);

        messageService.sendMessage("c1", B, new SendMessageRequest("after"));

        assertThat(inboxIds(A)).containsExactly("c1");
        ConversationResponse view = conversationService.getById(A, "c1");
        assertThat(view.inboxHidden()).isFalse();
        assertThat(view.lastMessageContent()).isEqualTo("after");
        assertThat(view.hasUnread()).isTrue();
        assertThat(messageService.listMessages("c1", A, null, 20).items()).extracting(MessageResponse::content)
                .containsExactly("after");
    }

    @Test
    void openingTheThreadAgainDoesNotRestoreHistoryAndDeletingTwiceChangesNothingElse() {
        conversation("c1", B);
        messageService.sendMessage("c1", B, new SendMessageRequest("old"));
        prefsService.deleteForMe(A, "c1");
        Instant first = prefsRepository.findById(A).orElseThrow().clearedAt("c1");

        conversationService.getOrCreate(A, B);
        prefsService.deleteForMe(A, "c1");

        assertThat(messageService.listMessages("c1", A, null, 20).items()).isEmpty();
        assertThat(prefsRepository.findById(A).orElseThrow().clearedAt("c1")).isAfterOrEqualTo(first);
    }

    @Test
    void quotesAndPagingNeverRevealWhatWasDeleted() throws Exception {
        conversation("c1", B);
        MessageResponse old = messageService.sendMessage("c1", B, new SendMessageRequest("secret old"));
        for (int i = 0; i < 4; i++) {
            messageService.sendMessage("c1", B, new SendMessageRequest("filler " + i));
        }
        prefsService.deleteForMe(A, "c1");
        Thread.sleep(5);

        // A cannot reply to, react to, recall or hide anything from before the deletion...
        assertThatThrownBy(() -> messageService.sendMessage("c1", A, new SendMessageRequest("x").replyingTo(old.id())))
                .isInstanceOf(com.tiktok.chatservice.exception.InvalidReplyTargetException.class);
        assertThatThrownBy(() -> messageService.react("c1", A, old.id(), "👍")).isInstanceOf(MessageNotFoundException.class);
        assertThatThrownBy(() -> messageService.hide("c1", A, old.id())).isInstanceOf(MessageNotFoundException.class);
        // ...and B replying to it later shows A an unavailable quote, not its text.
        messageService.sendMessage("c1", B, new SendMessageRequest("answering").replyingTo(old.id()));

        var page = messageService.listMessages("c1", A, null, 20);
        assertThat(page.items()).hasSize(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.items().get(0).replyTo().state()).isEqualTo(ReplyQuote.State.UNAVAILABLE);
        assertThat(page.items().get(0).replyTo().content()).isNull();
        // B still sees the original quoted.
        assertThat(messageService.listMessages("c1", B, null, 20).items().get(0).replyTo().content()).isEqualTo("secret old");
    }

    @Test
    void pagingWithAHistoryBoundaryKeepsCursorAndHasMoreExact() throws Exception {
        conversation("c1", B);
        for (int i = 0; i < 6; i++) {
            messageService.sendMessage("c1", B, new SendMessageRequest("old " + i));
        }
        prefsService.deleteForMe(A, "c1");
        Thread.sleep(5);
        for (int i = 0; i < 5; i++) {
            messageService.sendMessage("c1", B, new SendMessageRequest("new " + i));
        }

        var first = messageService.listMessages("c1", A, null, 3);
        var second = messageService.listMessages("c1", A, first.nextCursor(), 3);

        assertThat(first.items()).hasSize(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.items()).extracting(MessageResponse::content).containsExactly("new 1", "new 0");
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void deletingReturnsThePinSlotAndKeepsTheMute() {
        for (int i = 1; i <= 4; i++) {
            conversation("c" + i, 10 + i);
        }
        prefsService.pin(A, "c1");
        prefsService.pin(A, "c2");
        prefsService.pin(A, "c3");
        prefsService.mute(A, "c1", MuteRequest.Length.INDEFINITE);

        prefsService.deleteForMe(A, "c1");

        assertThat(prefsRepository.findById(A).orElseThrow().pinnedAt("c1")).isNull();
        assertThat(prefsService.pin(A, "c4").pinnedAt()).isNotNull();
        assertThat(conversationService.getById(A, "c1").muteMode()).isEqualTo(MuteMode.INDEFINITE);
    }

    @Test
    void deletingARequestIsNotDecliningItAndGivesNoOneATurn() {
        conversationRepository.save(Conversation.builder().id("r1").participantIds(List.of(A, 9L)).participantKey("1_9")
                .initiatorId(A).requestState(MessageRequestState.PENDING).build());
        messageService.sendMessage("r1", A, new SendMessageRequest("please accept"));

        prefsService.deleteForMe(A, "r1");

        Conversation stored = conversationRepository.findById("r1").orElseThrow();
        assertThat(stored.getRequestState()).isEqualTo(MessageRequestState.PENDING);
        assertThatThrownBy(() -> messageService.sendMessage("r1", A, new SendMessageRequest("again")))
                .isInstanceOf(MessageRequestLimitReachedException.class);
    }

    // ---- block

    @Test
    void aBlockStopsSendingBothWaysEvenInAConversationThatHadHistory() {
        conversation("c1", B);
        messageService.sendMessage("c1", A, new SendMessageRequest("hi"));
        doThrow(new MessagingBlockedException()).when(blockClient).requireNotBlocked(any(), any());

        assertThatThrownBy(() -> messageService.sendMessage("c1", A, new SendMessageRequest("x"))).isInstanceOf(MessagingBlockedException.class);
        assertThatThrownBy(() -> messageService.sendMessage("c1", B, new SendMessageRequest("y"))).isInstanceOf(MessagingBlockedException.class);
        // The history is not deleted by a block.
        assertThat(messageService.listMessages("c1", A, null, 20).items()).hasSize(1);
    }
}
