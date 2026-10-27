package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.DmAudience;
import com.tiktok.chatservice.client.FriendshipClient;
import com.tiktok.chatservice.client.SettingsClient;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.dto.response.MessageRequestSummaryResponse;
import com.tiktok.chatservice.dto.response.SendPermission;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.exception.DmSettingsUnavailableException;
import com.tiktok.chatservice.exception.MessageRequestNotPendingException;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.chatservice.exception.MessagingNotAllowedException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.exception.NotRequestRecipientException;
import com.tiktok.chatservice.mapper.ConversationMapperImpl;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How a conversation becomes a message request, and how its recipient answers one. A = 1 opens, B = 2 receives. */
@ExtendWith(MockitoExtension.class)
class MessageRequestServiceTest {

    private static final long A = 1L;
    private static final long B = 2L;

    @Mock
    private ConversationRepository conversationRepository;
    @Mock
    private BlockClient blockClient;
    @Mock
    private SettingsClient settingsClient;
    @Mock
    private FriendshipClient friendshipClient;
    @Mock
    private ChatEventPublisher eventPublisher;

    private ConversationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConversationServiceImpl(conversationRepository, blockClient, settingsClient,
                friendshipClient, eventPublisher,
                new ConversationViews(new ConversationMapperImpl(), org.mockito.Mockito.mock(com.tiktok.chatservice.repository.MessageRepository.class), org.mockito.Mockito.mock(ChatUserPrefsRepository.class)));
        lenient().when(settingsClient.dmAudience(any())).thenReturn(DmAudience.EVERYONE);
        lenient().when(conversationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Conversation.ConversationBuilder<?, ?> conversation() {
        return Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2")
                .initiatorId(A).lastReadAt(Map.of());
    }

    private void stored(Conversation conversation) {
        when(conversationRepository.findByIdAndDeletedAtIsNull("c1")).thenReturn(Optional.of(conversation));
    }

    private ConversationResponse open(boolean aFollowsB, boolean bFollowsA) {
        when(friendshipClient.areFriends(A, B)).thenReturn(aFollowsB && bFollowsA);
        return service.getOrCreate(A, B);
    }

    // --- the four follow cases, audience EVERYONE ---------------------------------------------

    @Test
    void neitherFollows_opensAPendingRequestFromTheOpener() {
        ConversationResponse response = open(false, false);
        assertThat(response.requestState()).isEqualTo(MessageRequestState.PENDING);
        assertThat(response.initiatorId()).isEqualTo(A);
        assertThat(response.sendPermission()).isEqualTo(SendPermission.FIRST_MESSAGE_ONLY);
    }

    @Test
    void onlyAFollowsB_isStillARequest() {
        assertThat(open(true, false).requestState()).isEqualTo(MessageRequestState.PENDING);
    }

    @Test
    void onlyBFollowsA_isStillARequest() {
        assertThat(open(false, true).requestState()).isEqualTo(MessageRequestState.PENDING);
    }

    @Test
    void mutualFollowers_getAnOrdinaryConversation() {
        ConversationResponse response = open(true, true);
        assertThat(response.requestState()).isNull();
        assertThat(response.sendPermission()).isEqualTo(SendPermission.ALLOWED);
    }

    /** A follow request still waiting on a private account is not a follow: user-service answers friends=false. */
    @Test
    void aFollowRequestWaitingOnAPrivateAccountDoesNotCountAsFollowing() {
        assertThat(open(false, true).requestState()).isEqualTo(MessageRequestState.PENDING);
    }

    // --- audience and block keep their meaning -------------------------------------------------

    @Test
    void friendsOnlyRecipient_stillRefusesANonMutualOpener() {
        when(settingsClient.dmAudience(B)).thenReturn(DmAudience.FRIENDS);
        when(friendshipClient.areFriends(A, B)).thenReturn(false);

        assertThatThrownBy(() -> service.getOrCreate(A, B)).isInstanceOf(MessagingNotAllowedException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void friendsOnlyRecipient_opensAnOrdinaryConversationForMutualFollowers() {
        when(settingsClient.dmAudience(B)).thenReturn(DmAudience.FRIENDS);
        assertThat(open(true, true).requestState()).isNull();
    }

    @Test
    void noOneRecipient_refusesEvenMutualFollowers() {
        when(settingsClient.dmAudience(B)).thenReturn(DmAudience.NO_ONE);

        assertThatThrownBy(() -> service.getOrCreate(A, B)).isInstanceOf(MessagingNotAllowedException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void aBlockIsCheckedFirstAndCreatesNothing() {
        doThrow(new MessagingBlockedException()).when(blockClient).requireNotBlocked(A, B);

        assertThatThrownBy(() -> service.getOrCreate(A, B)).isInstanceOf(MessagingBlockedException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void whenFriendshipCannotBeRead_nothingIsCreated() {
        when(friendshipClient.areFriends(A, B)).thenThrow(new DmSettingsUnavailableException(null));

        assertThatThrownBy(() -> service.getOrCreate(A, B)).isInstanceOf(DmSettingsUnavailableException.class);
        verify(conversationRepository, never()).save(any());
    }

    // --- reopening ----------------------------------------------------------------------------

    @Test
    void reopeningAPendingRequestAsTheInitiatorChangesNothingAndCreatesNoSecondRequest() {
        Conversation pending = conversation().requestState(MessageRequestState.PENDING)
                .lastMessageAt(Instant.now()).build();
        when(conversationRepository.findByParticipantKeyAndDeletedAtIsNull("1_2")).thenReturn(Optional.of(pending));
        when(friendshipClient.areFriends(A, B)).thenReturn(false);

        ConversationResponse response = service.getOrCreate(A, B);

        assertThat(response.sendPermission()).isEqualTo(SendPermission.REQUEST_SENT);
        verify(conversationRepository, never()).save(any());
    }

    /** The initiator is never told the request was deleted, and reopening must not undo it. */
    @Test
    void reopeningADeletedRequestAsTheInitiatorStillLooksUnanswered() {
        Conversation declined = conversation().requestState(MessageRequestState.DECLINED)
                .lastMessageAt(Instant.now()).build();
        when(conversationRepository.findByParticipantKeyAndDeletedAtIsNull("1_2")).thenReturn(Optional.of(declined));

        ConversationResponse response = service.getOrCreate(A, B);

        assertThat(response.sendPermission()).isEqualTo(SendPermission.REQUEST_SENT);
        verify(conversationRepository, never()).reopenDeclined(any(), any(), any());
    }

    /** The profile's Message button must not quietly let the other side back in. */
    @Test
    void theDeciderOpeningAThreadFromTheirProfileDoesNotRestoreIt() {
        Conversation declined = conversation().requestState(MessageRequestState.DECLINED)
                .lastMessageAt(Instant.now()).build();
        when(conversationRepository.findByParticipantKeyAndDeletedAtIsNull("1_2")).thenReturn(Optional.of(declined));

        ConversationResponse response = service.getOrCreate(B, A);

        assertThat(response.requestState()).isEqualTo(MessageRequestState.DECLINED);
        assertThat(response.sendPermission()).isEqualTo(SendPermission.REOPEN_ON_SEND);
        verify(conversationRepository, never()).reopenDeclined(any(), any(), any());
        verify(eventPublisher, never()).conversationChanged(any());
    }

    @Test
    void sendingIntoADeletedRequestReopensItAndTellsBothSides() {
        Conversation declined = conversation().requestState(MessageRequestState.DECLINED)
                .lastMessageAt(Instant.now()).build();
        Conversation ordinary = conversation().lastMessageAt(declined.getLastMessageAt()).build();
        when(conversationRepository.reopenDeclined(eq("c1"), eq(B), any())).thenReturn(true);
        when(conversationRepository.findByIdAndDeletedAtIsNull("c1")).thenReturn(Optional.of(ordinary));

        Conversation result = service.reopenForSend(declined, B);

        assertThat(result.getRequestState()).isNull();
        verify(eventPublisher).conversationChanged(ordinary);
    }

    @Test
    void reopeningForSendStillObeysTheOtherAccountsAudience() {
        Conversation declined = conversation().requestState(MessageRequestState.DECLINED).build();
        when(settingsClient.dmAudience(A)).thenReturn(DmAudience.NO_ONE);

        assertThatThrownBy(() -> service.reopenForSend(declined, B)).isInstanceOf(MessagingNotAllowedException.class);
        verify(conversationRepository, never()).reopenDeclined(any(), any(), any());
    }

    @Test
    void reopeningForSendIsIdempotentAndOnlyForTheDecider() {
        Conversation ordinary = conversation().build();
        assertThat(service.reopenForSend(ordinary, B)).isSameAs(ordinary);

        Conversation declined = conversation().requestState(MessageRequestState.DECLINED).build();
        assertThat(service.reopenForSend(declined, A)).isSameAs(declined);
        verify(conversationRepository, never()).reopenDeclined(any(), any(), any());
    }

    @Test
    void aPendingRequestWhoseSidesNowFollowEachOtherIsPromotedAndBothSidesAreTold() {
        Conversation pending = conversation().requestState(MessageRequestState.PENDING)
                .lastMessageAt(Instant.now()).build();
        Conversation ordinary = conversation().lastMessageAt(pending.getLastMessageAt()).build();
        when(friendshipClient.areFriends(A, B)).thenReturn(true);
        when(conversationRepository.acceptRequest(eq("c1"), eq(null), any())).thenReturn(true);
        when(conversationRepository.findByIdAndDeletedAtIsNull("c1")).thenReturn(Optional.of(ordinary));

        Conversation result = service.promoteIfMutual(pending);

        assertThat(result.getRequestState()).isNull();
        verify(eventPublisher).conversationChanged(ordinary);
    }

    @Test
    void promotionIsSkippedQuietlyWhenFriendshipCannotBeRead() {
        Conversation pending = conversation().requestState(MessageRequestState.PENDING).build();
        when(friendshipClient.areFriends(A, B)).thenThrow(new DmSettingsUnavailableException(null));

        assertThat(service.promoteIfMutual(pending)).isSameAs(pending);
        verify(conversationRepository, never()).acceptRequest(any(), any(), any());
    }

    // --- Accept / Delete ----------------------------------------------------------------------

    @Test
    void theRecipientAcceptsAndBothSidesAreToldOnce() {
        Conversation pending = conversation().requestState(MessageRequestState.PENDING)
                .lastMessageAt(Instant.now()).build();
        Conversation accepted = conversation().lastMessageAt(pending.getLastMessageAt()).build();
        when(conversationRepository.findByIdAndDeletedAtIsNull("c1"))
                .thenReturn(Optional.of(pending), Optional.of(accepted));
        when(conversationRepository.acceptRequest(eq("c1"), eq(B), any())).thenReturn(true);

        ConversationResponse response = service.acceptRequest(B, "c1");

        assertThat(response.requestState()).isNull();
        assertThat(response.sendPermission()).isEqualTo(SendPermission.ALLOWED);
        verify(eventPublisher).conversationChanged(accepted);
    }

    @Test
    void acceptingTwiceIsHarmlessAndTellsNobodyTwice() {
        Conversation accepted = conversation().build();
        stored(accepted);

        assertThat(service.acceptRequest(B, "c1").requestState()).isNull();
        verify(conversationRepository, never()).acceptRequest(any(), any(), any());
        verify(eventPublisher, never()).conversationChanged(any());
    }

    @Test
    void theInitiatorCannotAnswerTheirOwnRequest() {
        stored(conversation().requestState(MessageRequestState.PENDING).build());

        assertThatThrownBy(() -> service.acceptRequest(A, "c1")).isInstanceOf(NotRequestRecipientException.class);
        assertThatThrownBy(() -> service.declineRequest(A, "c1")).isInstanceOf(NotRequestRecipientException.class);
        verify(conversationRepository, never()).acceptRequest(any(), any(), any());
        verify(conversationRepository, never()).declineRequest(any(), any(), any());
    }

    @Test
    void anOutsiderCannotAnswerAnyonesRequest() {
        stored(conversation().requestState(MessageRequestState.PENDING).build());

        assertThatThrownBy(() -> service.acceptRequest(99L, "c1")).isInstanceOf(NotConversationParticipantException.class);
        assertThatThrownBy(() -> service.declineRequest(99L, "c1")).isInstanceOf(NotConversationParticipantException.class);
    }

    @Test
    void deletingMarksItDeclinedWithoutRemovingAnything() {
        Conversation pending = conversation().requestState(MessageRequestState.PENDING)
                .lastMessageAt(Instant.now()).build();
        Conversation declined = conversation().requestState(MessageRequestState.DECLINED)
                .lastMessageAt(pending.getLastMessageAt()).build();
        when(conversationRepository.findByIdAndDeletedAtIsNull("c1"))
                .thenReturn(Optional.of(pending), Optional.of(declined));
        when(conversationRepository.declineRequest(eq("c1"), eq(B), any())).thenReturn(true);

        ConversationResponse response = service.declineRequest(B, "c1");

        assertThat(response.sendPermission()).isEqualTo(SendPermission.REOPEN_ON_SEND);
        verify(eventPublisher).conversationChanged(declined);
        verify(conversationRepository, never()).delete(any());
        verify(conversationRepository, never()).deleteById(any());
    }

    @Test
    void deletingTwiceIsHarmless() {
        stored(conversation().requestState(MessageRequestState.DECLINED).build());

        assertThat(service.declineRequest(B, "c1").requestState()).isEqualTo(MessageRequestState.DECLINED);
        verify(conversationRepository, never()).declineRequest(any(), any(), any());
    }

    @Test
    void anAcceptedRequestCannotBeDeletedAndADeletedOneCannotBeAccepted() {
        stored(conversation().build());
        assertThatThrownBy(() -> service.declineRequest(B, "c1")).isInstanceOf(MessageRequestNotPendingException.class);

        stored(conversation().requestState(MessageRequestState.DECLINED).build());
        assertThatThrownBy(() -> service.acceptRequest(B, "c1")).isInstanceOf(MessageRequestNotPendingException.class);
    }

    /** The recipient's read said PENDING; the update found it already answered the other way. */
    @Test
    void whenADeleteWinsTheRaceTheAcceptFailsInsteadOfPretendingToSucceed() {
        Conversation pending = conversation().requestState(MessageRequestState.PENDING).build();
        Conversation declined = conversation().requestState(MessageRequestState.DECLINED).build();
        when(conversationRepository.findByIdAndDeletedAtIsNull("c1"))
                .thenReturn(Optional.of(pending), Optional.of(declined));
        when(conversationRepository.acceptRequest(eq("c1"), eq(B), any())).thenReturn(false);

        assertThatThrownBy(() -> service.acceptRequest(B, "c1")).isInstanceOf(MessageRequestNotPendingException.class);
        verify(eventPublisher, never()).conversationChanged(any());
    }

    @Test
    void whenAnAcceptWinsTheRaceTheDeleteFails() {
        Conversation pending = conversation().requestState(MessageRequestState.PENDING).build();
        Conversation accepted = conversation().build();
        when(conversationRepository.findByIdAndDeletedAtIsNull("c1"))
                .thenReturn(Optional.of(pending), Optional.of(accepted));
        when(conversationRepository.declineRequest(eq("c1"), eq(B), any())).thenReturn(false);

        assertThatThrownBy(() -> service.declineRequest(B, "c1")).isInstanceOf(MessageRequestNotPendingException.class);
    }

    // --- the two counters --------------------------------------------------------------------

    @Test
    void pendingAndUnreadRequestsAreDifferentNumbers() {
        Instant sent = Instant.parse("2026-10-26T12:00:00Z");
        Conversation unopened = conversation().id("c1").requestState(MessageRequestState.PENDING)
                .lastMessageAt(sent).build();
        Conversation opened = conversation().id("c2").requestState(MessageRequestState.PENDING)
                .lastMessageAt(sent).lastReadAt(Map.of("2", sent.plusSeconds(5))).build();
        when(conversationRepository.findIncomingRequests(B)).thenReturn(List.of(unopened, opened));

        MessageRequestSummaryResponse summary = service.requestSummary(B);

        assertThat(summary.pending()).isEqualTo(2);
        assertThat(summary.unread()).isEqualTo(1);
    }

    @Test
    void theInboxListAsksForTheInboxNotEveryConversation() {
        when(conversationRepository.findInbox(B)).thenReturn(List.of());

        assertThat(service.listForUser(B)).isEmpty();
        verify(conversationRepository).findInbox(B);
        ArgumentCaptor<Long> user = ArgumentCaptor.forClass(Long.class);
        verify(conversationRepository, never()).findByParticipantIdsAndDeletedAtIsNullOrderByLastMessageAtDesc(user.capture());
    }
}
