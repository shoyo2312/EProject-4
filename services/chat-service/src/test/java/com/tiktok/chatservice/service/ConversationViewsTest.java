package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.MuteMode;
import com.tiktok.chatservice.mapper.ConversationMapperImpl;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConversationViewsTest {
    private final BlockClient blocks = mock(BlockClient.class);
    private final ConversationViews views = new ConversationViews(new ConversationMapperImpl(),
            mock(MessageRepository.class), mock(ChatUserPrefsRepository.class), blocks);

    private Conversation conversation(String id, Long other) {
        return Conversation.builder().id(id).participantIds(List.of(1L, other)).build();
    }

    @Test
    void inbox_usesOneBatchAndOnlyExposesTheViewersDirection() {
        when(blocks.statesOf(1L, List.of(2L, 3L))).thenReturn(Map.of(
                2L, new BlockClient.BlockState(false, true, 42),
                3L, new BlockClient.BlockState(true, false, 43)));
        var inbox = views.inbox(List.of(conversation("a", 2L), conversation("b", 3L)), 1L);
        verify(blocks).statesOf(1L, List.of(2L, 3L));
        verifyNoMoreInteractions(blocks);
        var otherBlocked = inbox.stream().filter(v -> v.id().equals("a")).findFirst().orElseThrow();
        assertThat(otherBlocked.blockedByViewer()).isFalse();
        assertThat(otherBlocked.messagingBlocked()).isTrue();
        assertThat(otherBlocked.blockVersion()).isEqualTo(42);
        String json = JsonMapper.builder().findAndAddModules().build().writeValueAsString(otherBlocked);
        assertThat(json).contains("\"blockedByViewer\":false", "\"messagingBlocked\":true")
                .doesNotContain("\"blockedViewer\"");
        assertThat(inbox.stream().filter(v -> v.id().equals("b")).findFirst().orElseThrow().blockedByViewer()).isTrue();
    }

    @Test
    void singleView_usesOneLookup() {
        when(blocks.statesOf(1L, List.of(2L))).thenReturn(Map.of(2L, new BlockClient.BlockState(false, false, 17)));
        var view = views.of(conversation("a", 2L), 1L);
        assertThat(view.blockedByViewer()).isFalse();
        assertThat(view.messagingBlocked()).isFalse();
        assertThat(view.blockVersion()).isEqualTo(17);
        verify(blocks).statesOf(1L, List.of(2L));
        verifyNoMoreInteractions(blocks);
    }

    @Test
    void unavailableLookup_keepsTheResponseWithUnknownFields() {
        when(blocks.statesOf(1L, List.of(2L, 3L))).thenReturn(Map.of());
        assertThat(views.inbox(List.of(conversation("a", 2L), conversation("b", 3L)), 1L))
                .hasSize(2).allSatisfy(view -> {
                    assertThat(view.blockedByViewer()).isNull();
                    assertThat(view.messagingBlocked()).isNull();
                    assertThat(view.blockVersion()).isNull();
                });
        assertThat(views.of(conversation("a", 2L), 1L).blockVersion()).isNull();
    }

    @Test
    void constructorsDefaultToKnownUnblocked_andCopiesPreserveUnknownOrKnownState() {
        ConversationResponse plain = new ConversationMapperImpl().toResponse(conversation("a", 2L), 1L);
        assertThat(plain.blockedByViewer()).isFalse();
        assertThat(plain.messagingBlocked()).isFalse();
        assertThat(plain.blockVersion()).isZero();
        for (var state : List.of(plain.withBlockState(false, true, 91L), plain.withBlockState(null, null, null))) {
            var copied = state.withPreview(null, null, null, false)
                    .withViewerChoices(null, MuteMode.NONE, null, false);
            assertThat(copied.blockedByViewer()).isEqualTo(state.blockedByViewer());
            assertThat(copied.messagingBlocked()).isEqualTo(state.messagingBlocked());
            assertThat(copied.blockVersion()).isEqualTo(state.blockVersion());
        }
    }
}
