package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.response.SendPermission;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MessageRequestState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SendPolicyTest {

    private static final long A = 1L;
    private static final long B = 2L;

    private static Conversation.ConversationBuilder<?, ?> base() {
        return Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2");
    }

    @Test
    void anOrdinaryConversationAllowsBothSides() {
        Conversation conversation = base().initiatorId(A).build();
        assertThat(SendPolicy.of(conversation, A)).isEqualTo(SendPermission.ALLOWED);
        assertThat(SendPolicy.of(conversation, B)).isEqualTo(SendPermission.ALLOWED);
    }

    /** Threads that predate requests have neither field; they must keep working exactly as before. */
    @Test
    void aLegacyConversationWithNoRequestFieldsIsOrdinary() {
        Conversation legacy = base().lastMessageAt(Instant.parse("2026-01-01T00:00:00Z")).build();
        assertThat(legacy.getRequestState()).isNull();
        assertThat(legacy.getInitiatorId()).isNull();
        assertThat(SendPolicy.of(legacy, A)).isEqualTo(SendPermission.ALLOWED);
        assertThat(SendPolicy.of(legacy, B)).isEqualTo(SendPermission.ALLOWED);
    }

    @Test
    void aPendingRequestGivesTheInitiatorOneMessageAndTheRecipientNone() {
        Conversation fresh = base().initiatorId(A).requestState(MessageRequestState.PENDING).build();
        assertThat(SendPolicy.of(fresh, A)).isEqualTo(SendPermission.FIRST_MESSAGE_ONLY);
        assertThat(SendPolicy.of(fresh, B)).isEqualTo(SendPermission.AWAITING_ACCEPT);
    }

    @Test
    void onceTheFirstMessageIsStoredTheInitiatorIsLockedOut() {
        Conversation sent = base().initiatorId(A).requestState(MessageRequestState.PENDING)
                .lastMessageAt(Instant.now()).build();
        assertThat(SendPolicy.of(sent, A)).isEqualTo(SendPermission.REQUEST_SENT);
        assertThat(SendPolicy.of(sent, B)).isEqualTo(SendPermission.AWAITING_ACCEPT);
    }

    /** Deleting is silent: the initiator sees what they saw before; the decliner may reach out by sending. */
    @Test
    void aDeletedRequestLooksUnansweredToTheInitiatorAndReopensWhenTheDeciderSends() {
        Conversation declined = base().initiatorId(A).requestState(MessageRequestState.DECLINED)
                .lastMessageAt(Instant.now()).build();
        assertThat(SendPolicy.of(declined, A)).isEqualTo(SendPermission.REQUEST_SENT);
        assertThat(SendPolicy.of(declined, B)).isEqualTo(SendPermission.REOPEN_ON_SEND);
    }

    private static final Instant SENT = Instant.parse("2026-10-01T09:00:00Z");

    @Test
    void theInitiatorWaitsFifteenDaysBeforeAnotherMessage() {
        Conversation sent = base().initiatorId(A).requestState(MessageRequestState.PENDING).lastMessageAt(SENT).build();

        assertThat(SendPolicy.of(sent, A, SENT.plus(java.time.Duration.ofDays(15)).minusSeconds(1)))
                .isEqualTo(SendPermission.REQUEST_SENT);
        assertThat(SendPolicy.of(sent, A, SENT.plus(java.time.Duration.ofDays(15))))
                .isEqualTo(SendPermission.FIRST_MESSAGE_ONLY);
        assertThat(SendPolicy.retryAvailableAt(sent, A)).isEqualTo(SENT.plus(java.time.Duration.ofDays(15)));
    }

    /** Ignored and deleted look the same to the sender, so both get the same second chance. */
    @Test
    void aDeletedRequestAlsoGetsASecondRoundAfterFifteenDays() {
        Conversation declined = base().initiatorId(A).requestState(MessageRequestState.DECLINED).lastMessageAt(SENT).build();

        assertThat(SendPolicy.of(declined, A, SENT.plus(java.time.Duration.ofDays(14)))).isEqualTo(SendPermission.REQUEST_SENT);
        assertThat(SendPolicy.of(declined, A, SENT.plus(java.time.Duration.ofDays(16)))).isEqualTo(SendPermission.FIRST_MESSAGE_ONLY);
    }

    @Test
    void theRecipientIsNeverGivenARetryClock() {
        Conversation sent = base().initiatorId(A).requestState(MessageRequestState.PENDING).lastMessageAt(SENT).build();
        assertThat(SendPolicy.retryAvailableAt(sent, B)).isNull();
        assertThat(SendPolicy.of(sent, B, SENT.plus(java.time.Duration.ofDays(60)))).isEqualTo(SendPermission.AWAITING_ACCEPT);
    }
}
