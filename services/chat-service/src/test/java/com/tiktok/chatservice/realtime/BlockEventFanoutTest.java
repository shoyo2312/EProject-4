package com.tiktok.chatservice.realtime;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.response.ChatEvent;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.mapper.ConversationMapperImpl;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import com.tiktok.chatservice.service.ChatEventPublisher;
import com.tiktok.chatservice.service.ConversationViews;
import com.tiktok.event.user.UserBlockChangedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BlockEventFanoutTest {
    private final ConversationRepository repository = mock(ConversationRepository.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final BlockClient blocks = mock(BlockClient.class);
    private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
    private final ConversationViews views = new ConversationViews(new ConversationMapperImpl(),
            mock(MessageRepository.class), mock(ChatUserPrefsRepository.class), blocks);
    private final BlockEventFanout fanout = new BlockEventFanout(mapper, repository,
            new ChatEventPublisher(messaging, views));

    @Test
    void bothParticipantsGetPrivateCurrentViews_evenForDuplicateOrOldEvents() {
        var conversation = Conversation.builder().id("c1").participantIds(List.of(1L, 2L)).build();
        when(repository.findByParticipantKeyAndDeletedAtIsNull("1_2")).thenReturn(Optional.of(conversation));
        when(blocks.statesOf(1L, List.of(2L))).thenReturn(Map.of(2L, new BlockClient.BlockState(false, true, 42)));
        when(blocks.statesOf(2L, List.of(1L))).thenReturn(Map.of(1L, new BlockClient.BlockState(true, false, 42)));
        // The old event says unblocked; only the current lookup determines the view.
        String payload = mapper.writeValueAsString(UserBlockChangedEvent.of(2L, 1L, false));
        fanout.onBlockChanged(payload);
        fanout.onBlockChanged(payload);
        ArgumentCaptor<ChatEvent> a = ArgumentCaptor.forClass(ChatEvent.class);
        ArgumentCaptor<ChatEvent> b = ArgumentCaptor.forClass(ChatEvent.class);
        verify(messaging, times(2)).convertAndSendToUser(eq("1"), eq("/queue/chat"), a.capture());
        verify(messaging, times(2)).convertAndSendToUser(eq("2"), eq("/queue/chat"), b.capture());
        assertThat(a.getAllValues()).allSatisfy(frame -> {
            assertThat(frame.type()).isEqualTo(ChatEvent.Type.CONVERSATION);
            assertThat(frame.conversation().blockedByViewer()).isFalse();
            assertThat(frame.conversation().messagingBlocked()).isTrue();
            assertThat(frame.conversation().blockVersion()).isEqualTo(42);
        });
        assertThat(b.getValue().conversation().blockedByViewer()).isTrue();
        verifyNoMoreInteractions(messaging);
    }

    @Test
    void noConversation_sendsNothing() {
        fanout.onBlockChanged(mapper.writeValueAsString(UserBlockChangedEvent.of(1L, 2L, true)));
        verify(repository).findByParticipantKeyAndDeletedAtIsNull("1_2");
        verifyNoInteractions(messaging, blocks);
    }

    @Test
    void unreadableOrMissingPair_isDropped() {
        fanout.onBlockChanged("not json");
        fanout.onBlockChanged("{}");
        fanout.onBlockChanged("null");
        verifyNoInteractions(repository, messaging, blocks);
    }
}
