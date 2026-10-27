package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.DmAudience;
import com.tiktok.chatservice.client.FriendshipClient;
import com.tiktok.chatservice.client.SettingsClient;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.exception.CannotMessageSelfException;
import com.tiktok.chatservice.exception.ConversationNotFoundException;
import com.tiktok.chatservice.exception.DmSettingsUnavailableException;
import com.tiktok.chatservice.exception.MessagingNotAllowedException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.mapper.ConversationMapper;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
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

@ExtendWith(MockitoExtension.class)
class ConversationServiceImplTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ConversationMapper conversationMapper;

    private ConversationServiceImpl conversationService;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        conversationService = new ConversationServiceImpl(conversationRepository, blockClient, settingsClient,
                friendshipClient, eventPublisher,
                new ConversationViews(conversationMapper, org.mockito.Mockito.mock(com.tiktok.chatservice.repository.MessageRepository.class), org.mockito.Mockito.mock(ChatUserPrefsRepository.class)));
        // The audience check runs on every open, so the permissive answer is the baseline every
        // test that is not about it needs. Lenient: the self and block cases never reach it.
        lenient().when(settingsClient.dmAudience(any())).thenReturn(DmAudience.EVERYONE);
    }

    @Mock
    private BlockClient blockClient;

    @Mock
    private ChatEventPublisher eventPublisher;

    @Mock
    private SettingsClient settingsClient;

    @Mock
    private FriendshipClient friendshipClient;

    @Test
    void getOrCreate_acrossABlock_isRefusedAndNothingIsCreated() {
        doThrow(new MessagingBlockedException()).when(blockClient).requireNotBlocked(1L, 2L);

        assertThatThrownBy(() -> conversationService.getOrCreate(1L, 2L))
                .isInstanceOf(MessagingBlockedException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void getOrCreate_sameUser_throws() {
        assertThatThrownBy(() -> conversationService.getOrCreate(1L, 1L))
                .isInstanceOf(CannotMessageSelfException.class);
    }

    @Test
    void getOrCreate_existingPair_reusesConversation() {
        String key = Conversation.buildParticipantKey(1L, 2L);
        Conversation existing = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).participantKey(key).build();
        when(conversationRepository.findByParticipantKeyAndDeletedAtIsNull(key)).thenReturn(Optional.of(existing));
        when(conversationMapper.toResponse(existing, 1L))
                .thenReturn(new ConversationResponse("c1", List.of(1L, 2L), null, null, null, null, null, false, null, null,
                        com.tiktok.chatservice.dto.response.SendPermission.ALLOWED, null));

        conversationService.getOrCreate(1L, 2L);

        verify(conversationRepository, never()).save(any());
    }

    @Test
    void getOrCreate_noExistingPair_createsConversationWithBothParticipants() {
        String key = Conversation.buildParticipantKey(1L, 2L);
        when(conversationRepository.findByParticipantKeyAndDeletedAtIsNull(key)).thenReturn(Optional.empty());
        when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversationMapper.toResponse(any(Conversation.class), eq(1L)))
                .thenReturn(new ConversationResponse("c1", List.of(1L, 2L), null, null, null, null, null, false, null, null,
                        com.tiktok.chatservice.dto.response.SendPermission.ALLOWED, null));

        conversationService.getOrCreate(1L, 2L);

        ArgumentCaptor<Conversation> captor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(captor.capture());
        assertThat(captor.getValue().getParticipantIds()).containsExactlyInAnyOrder(1L, 2L);
        assertThat(captor.getValue().getParticipantKey()).isEqualTo(key);
    }

    @Test
    void requireParticipant_notFound_throwsConversationNotFound() {
        when(conversationRepository.findByIdAndDeletedAtIsNull("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversationService.requireParticipant(1L, "missing"))
                .isInstanceOf(ConversationNotFoundException.class);
    }

    @Test
    void requireParticipant_notAParticipant_throwsForbidden() {
        Conversation conversation = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).build();
        when(conversationRepository.findByIdAndDeletedAtIsNull("c1")).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> conversationService.requireParticipant(99L, "c1"))
                .isInstanceOf(NotConversationParticipantException.class);
    }

    @Test
    void getOrCreate_whenTheRecipientAcceptsMessagesFromNobody_isRefusedAndNothingIsCreated() {
        when(settingsClient.dmAudience(2L)).thenReturn(DmAudience.NO_ONE);

        assertThatThrownBy(() -> conversationService.getOrCreate(1L, 2L))
                .isInstanceOf(MessagingNotAllowedException.class);
        verify(conversationRepository, never()).save(any());
    }

    @Test
    void getOrCreate_onAFriendsOnlyRecipient_opensForAMutualFollowerOnly() {
        when(settingsClient.dmAudience(2L)).thenReturn(DmAudience.FRIENDS);
        when(friendshipClient.areFriends(1L, 2L)).thenReturn(false);

        assertThatThrownBy(() -> conversationService.getOrCreate(1L, 2L))
                .isInstanceOf(MessagingNotAllowedException.class);

        verify(conversationRepository, never()).save(any());

        when(friendshipClient.areFriends(1L, 2L)).thenReturn(true);
        when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversationMapper.toResponse(any(Conversation.class), eq(1L)))
                .thenReturn(new ConversationResponse("c1", List.of(1L, 2L), null, null, null, null, null, false, null, null,
                        com.tiktok.chatservice.dto.response.SendPermission.ALLOWED, null));

        assertThat(conversationService.getOrCreate(1L, 2L).id()).isEqualTo("c1");
        verify(conversationRepository).save(any());
    }

    /**
     * Only the recipient's setting is consulted. A sender who accepts messages from nobody can
     * still start a conversation, the same way a video owner can comment on their own
     * comments-from-nobody video.
     */
    @Test
    void getOrCreate_neverConsultsTheSendersOwnAudience() {
        when(settingsClient.dmAudience(2L)).thenReturn(DmAudience.EVERYONE);
        when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        conversationService.getOrCreate(1L, 2L);

        verify(settingsClient).dmAudience(2L);
        verify(settingsClient, never()).dmAudience(1L);
    }

    /**
     * The audience decides whether a thread may be started, not whether one that exists may be
     * reopened. The Message button on a profile goes through here, and it used to be refused for a
     * thread the inbox still sent in — including one the recipient had started themselves.
     */
    @Test
    void getOrCreate_anExistingConversation_reopensWhateverTheRecipientsAudienceNowSays() {
        String key = Conversation.buildParticipantKey(1L, 2L);
        Conversation existing = Conversation.builder().id("c1").participantIds(List.of(1L, 2L))
                .participantKey(key).build();
        when(conversationRepository.findByParticipantKeyAndDeletedAtIsNull(key))
                .thenReturn(Optional.of(existing));
        when(conversationMapper.toResponse(existing, 1L))
                .thenReturn(new ConversationResponse("c1", List.of(1L, 2L), null, null, null, null, null, false, null, null,
                        com.tiktok.chatservice.dto.response.SendPermission.ALLOWED, null));

        assertThat(conversationService.getOrCreate(1L, 2L).id()).isEqualTo("c1");
        verify(settingsClient, never()).dmAudience(any());
        verify(friendshipClient, never()).areFriends(any(), any());
    }

    /** A block still stops a reopen: it is checked before the conversation is looked up. */
    @Test
    void getOrCreate_anExistingConversationAcrossABlock_isStillRefused() {
        doThrow(new MessagingBlockedException()).when(blockClient).requireNotBlocked(1L, 2L);

        assertThatThrownBy(() -> conversationService.getOrCreate(1L, 2L))
                .isInstanceOf(MessagingBlockedException.class);
        verify(conversationRepository, never()).findByParticipantKeyAndDeletedAtIsNull(any());
    }

    /**
     * Fails closed, like the block check above it: a user-service outage pauses new conversations
     * rather than opening one the recipient may have asked not to receive. The opposite of
     * interaction-service's comment audience, deliberately.
     */
    @Test
    void getOrCreate_whenTheSettingsLookupFails_refusesRatherThanOpening() {
        when(settingsClient.dmAudience(2L)).thenThrow(new DmSettingsUnavailableException(null));

        assertThatThrownBy(() -> conversationService.getOrCreate(1L, 2L))
                .isInstanceOf(DmSettingsUnavailableException.class);
        verify(conversationRepository, never()).save(any());
    }
}
