package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.exception.CannotMessageSelfException;
import com.tiktok.chatservice.exception.ConversationNotFoundException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.mapper.ConversationMapper;
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
        conversationService = new ConversationServiceImpl(conversationRepository, conversationMapper);
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
                .thenReturn(new ConversationResponse("c1", List.of(1L, 2L), null, null, null, null, false));

        conversationService.getOrCreate(1L, 2L);

        verify(conversationRepository, never()).save(any());
    }

    @Test
    void getOrCreate_noExistingPair_createsConversationWithBothParticipants() {
        String key = Conversation.buildParticipantKey(1L, 2L);
        when(conversationRepository.findByParticipantKeyAndDeletedAtIsNull(key)).thenReturn(Optional.empty());
        when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(conversationMapper.toResponse(any(Conversation.class), eq(1L)))
                .thenReturn(new ConversationResponse("c1", List.of(1L, 2L), null, null, null, null, false));

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
}
