package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.ReportEvidenceRequest;
import com.tiktok.chatservice.dto.response.ReportEvidenceResponse;
import com.tiktok.chatservice.entity.ChatUserPrefs;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.exception.InvalidMessageException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.exception.ReportEvidenceUnavailableException;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportEvidenceServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    @Mock
    private ConversationService conversationService;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ChatUserPrefsRepository prefsRepository;

    private ReportEvidenceService service;

    @BeforeEach
    void setUp() {
        service = new ReportEvidenceService(conversationService, messageRepository, prefsRepository);
    }

    private Conversation conversation(Long... participants) {
        return Conversation.builder().id("c1").participantIds(List.of(participants)).build();
    }

    private static Message.MessageBuilder<?, ?> draft(String id, Long sender, int minute) {
        return Message.builder().id(id).conversationId("c1").senderId(sender)
                .type(MessageType.TEXT).content("text " + id).sentAt(T0.plusSeconds(60L * minute));
    }

    private static Message message(String id, Long sender, int minute) {
        return draft(id, sender, minute).build();
    }

    private ReportEvidenceRequest request(String... ids) {
        return new ReportEvidenceRequest(1L, "c1", List.of(ids));
    }

    @Test
    void capturesServerSideAndDerivesTheReportedUser() {
        when(conversationService.requireParticipant(1L, "c1")).thenReturn(conversation(1L, 2L));
        when(prefsRepository.findById(1L)).thenReturn(Optional.empty());
        when(messageRepository.findInConversation(any(), anyCollection()))
                .thenReturn(List.of(message("m2", 1L, 2), message("m1", 2L, 1)));

        ReportEvidenceResponse response = service.capture(request("m2", "m1"));

        assertThat(response.reportedUserId()).isEqualTo(2L);
        assertThat(response.messages()).extracting(ReportEvidenceResponse.Item::messageId).containsExactly("m1", "m2");
        assertThat(response.messages().get(0).senderId()).isEqualTo(2L);
    }

    @Test
    void rejectsRecalledHiddenClearedForeignAndMissingMessages() {
        when(conversationService.requireParticipant(1L, "c1")).thenReturn(conversation(1L, 2L));
        ChatUserPrefs prefs = mock(ChatUserPrefs.class);
        when(prefs.clearedAt("c1")).thenReturn(T0.plusSeconds(60));
        when(prefsRepository.findById(1L)).thenReturn(Optional.of(prefs));
        Message recalled = draft("recalled", 2L, 5).recalledAt(T0).build();
        Message hidden = draft("hidden", 2L, 5).hiddenFor(List.of(1L)).build();
        Message deleted = draft("deleted", 2L, 5).deletedAt(T0).build();
        Message old = message("old", 2L, 1);
        when(messageRepository.findInConversation(any(), anyCollection()))
                .thenReturn(List.of(message("ok", 2L, 5), recalled, hidden, deleted, old));

        assertThatThrownBy(() -> service.capture(request("ok", "recalled", "hidden", "deleted", "old", "ghost")))
                .isInstanceOf(ReportEvidenceUnavailableException.class)
                .hasMessageContaining("recalled,hidden,deleted,old,ghost")
                .hasMessageNotContaining("ok,");
    }

    @Test
    void refusesNonParticipantsAndBadCounts() {
        when(conversationService.requireParticipant(1L, "c1")).thenThrow(new NotConversationParticipantException("c1"));
        assertThatThrownBy(() -> service.capture(request("m1"))).isInstanceOf(NotConversationParticipantException.class);

        assertThatThrownBy(() -> service.capture(request("m1", "m1"))).isInstanceOf(InvalidMessageException.class);
        assertThatThrownBy(() -> service.capture(new ReportEvidenceRequest(1L, "c1", List.of())))
                .isInstanceOf(InvalidMessageException.class);
        String[] fiftyOne = IntStream.rangeClosed(1, 51).mapToObj(i -> "m" + i).toArray(String[]::new);
        assertThatThrownBy(() -> service.capture(request(fiftyOne))).isInstanceOf(InvalidMessageException.class);
        verifyNoInteractions(messageRepository);
    }
}
