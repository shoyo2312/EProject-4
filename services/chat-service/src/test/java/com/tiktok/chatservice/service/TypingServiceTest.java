package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.response.ChatEvent;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Typing is a hint: held per session, expiring, refused quietly, and never more than who/where/on-off. */
class TypingServiceTest {

    private static final Long A = 1L;
    private static final Long B = 2L;

    private final ConversationService conversations = mock(ConversationService.class);
    private final BlockClient blocks = mock(BlockClient.class);
    private final ChatEventPublisher publisher = mock(ChatEventPublisher.class);
    private final AtomicLong now = new AtomicLong(1_000_000);
    private TypingService typing;

    @BeforeEach
    void setUp() {
        typing = new TypingService(conversations, blocks, publisher, now::get);
        when(conversations.requireParticipant(eq(A), eq("c1"))).thenReturn(ordinary());
    }

    private static Conversation ordinary() {
        return Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2").build();
    }

    private List<Boolean> sentToB() {
        ArgumentCaptor<ChatEvent> events = ArgumentCaptor.forClass(ChatEvent.class);
        verify(publisher, org.mockito.Mockito.atLeast(0)).toUser(eq(B), events.capture());
        return events.getAllValues().stream().map(e -> e.typing().typing()).toList();
    }

    @Test
    void typingGoesToTheOtherSideOnly_asWhoWhereAndOnOff() {
        typing.update("c1", A, "s1", true);

        ArgumentCaptor<ChatEvent> event = ArgumentCaptor.forClass(ChatEvent.class);
        verify(publisher).toUser(eq(B), event.capture());
        verify(publisher, never()).toUser(eq(A), any());
        assertThat(event.getValue().type()).isEqualTo(ChatEvent.Type.TYPING);
        assertThat(event.getValue().typing().conversationId()).isEqualTo("c1");
        assertThat(event.getValue().typing().userId()).isEqualTo(A);
        assertThat(event.getValue().typing().typing()).isTrue();
        assertThat(event.getValue().message()).isNull();
        assertThat(event.getValue().conversation()).isNull();
    }

    @Test
    void stopSaysSo() {
        typing.update("c1", A, "s1", true);
        typing.update("c1", A, "s1", false);

        assertThat(sentToB()).containsExactly(true, false);
    }

    @Test
    void aFasterClientIsIgnored_andRenewalsAreAcceptedOnceTheIntervalHasPassed() {
        typing.update("c1", A, "s1", true);
        now.addAndGet(200);
        typing.update("c1", A, "s1", true);
        verify(publisher, times(1)).toUser(eq(B), any());

        now.addAndGet(1_000);
        typing.update("c1", A, "s1", true);
        verify(publisher, times(2)).toUser(eq(B), any());
    }

    @Test
    void aSessionThatStopsRenewingRunsOutAfterTheTtl_andNeverStaysUpForever() {
        typing.update("c1", A, "s1", true);

        now.addAndGet(TypingService.TTL_MS - 1);
        typing.expire();
        assertThat(sentToB()).containsExactly(true);

        now.addAndGet(2);
        typing.expire();
        assertThat(sentToB()).containsExactly(true, false);

        // Expiring again says nothing more.
        typing.expire();
        assertThat(sentToB()).containsExactly(true, false);
    }

    @Test
    void oneTabStoppingDoesNotCancelAnotherThatIsStillTyping() {
        typing.update("c1", A, "tab1", true);
        now.addAndGet(1_500);
        typing.update("c1", A, "tab2", true);

        typing.update("c1", A, "tab1", false);
        assertThat(sentToB()).doesNotContain(false);

        typing.update("c1", A, "tab2", false);
        assertThat(sentToB()).endsWith(false);
    }

    @Test
    void aClosedSocketStopsItsTypingAtOnce_butNotAnotherSessions() {
        typing.update("c1", A, "tab1", true);
        now.addAndGet(1_500);
        typing.update("c1", A, "tab2", true);

        typing.sessionClosed("tab1");
        assertThat(sentToB()).doesNotContain(false);

        typing.sessionClosed("tab2");
        assertThat(sentToB()).endsWith(false);
    }

    @Test
    void aStaleStopForASessionThatNeverStartedChangesNothing() {
        typing.update("c1", A, "s1", false);
        verify(publisher, never()).toUser(any(), any());
    }

    @Test
    void aNonParticipantIsDroppedWithoutAWord() {
        when(conversations.requireParticipant(eq(A), eq("other"))).thenThrow(new NotConversationParticipantException("other"));

        typing.update("other", A, "s1", true);

        verify(publisher, never()).toUser(any(), any());
    }

    @Test
    void blockedMeansNothingIsSent_andAnythingAlreadyUpComesDown() {
        typing.update("c1", A, "s1", true);
        doThrow(new MessagingBlockedException()).when(blocks).requireNotBlocked(A, B);
        now.addAndGet(TypingService.ALLOW_CACHE_MS + 1_000);

        typing.update("c1", A, "s1", true);

        assertThat(sentToB()).containsExactly(true, false);
    }

    @Test
    void aFailedBlockCheckIsTreatedAsNo_notAsYes() {
        doThrow(new IllegalStateException("user-service down")).when(blocks).requireNotBlocked(A, B);

        typing.update("c1", A, "s1", true);

        verify(publisher, never()).toUser(any(), any());
    }

    @Test
    void noTypingBeforeTheConversationIsOpenToSendInto() {
        for (MessageRequestState state : MessageRequestState.values()) {
            Conversation request = Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2")
                    .requestState(state).initiatorId(B).build();
            when(conversations.requireParticipant(eq(A), eq("c1"))).thenReturn(request);
            clearInvocations(publisher);

            typing.update("c1", A, "s-" + state, true);

            // A is the recipient of B's request: awaiting accept, or a deleted one to be reopened first.
            verify(publisher, never()).toUser(any(), any());
        }
    }

    @Test
    void theInitiatorOfARequestThatIsStillPendingCannotTypeEither() {
        Conversation pending = Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2")
                .requestState(MessageRequestState.PENDING).initiatorId(A).build();
        when(conversations.requireParticipant(eq(A), eq("c1"))).thenReturn(pending);

        typing.update("c1", A, "s1", true);

        verify(publisher, never()).toUser(any(), any());
    }

    @Test
    void theCheckIsRemembered_soHeartbeatsDoNotHitTheDatabaseOrUserServiceEveryTime() {
        typing.update("c1", A, "s1", true);
        now.addAndGet(1_500);
        typing.update("c1", A, "s1", true);
        now.addAndGet(1_500);
        typing.update("c1", A, "s1", true);

        verify(conversations, times(1)).requireParticipant(A, "c1");
        verify(blocks, times(1)).requireNotBlocked(A, B);
        verify(publisher, atLeastOnce()).toUser(eq(B), any());
    }
}
