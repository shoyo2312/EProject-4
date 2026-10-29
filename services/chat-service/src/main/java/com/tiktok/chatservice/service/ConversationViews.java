package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.response.ConversationResponse;
import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.entity.ChatUserPrefs;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.mapper.ConversationMapper;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A conversation as one participant sees it: the mapper's answer, with the viewer's own choices laid
 * over it (pin, mute, deleted-from-my-side) and the preview replaced by their newest visible message
 * when they have deleted messages — or the whole conversation — on their side.
 *
 * <p>Only conversations in which the viewer actually deleted something pay the extra read; the rest
 * use the preview stored on the conversation. Only the preview text moves — {@code lastMessageAt},
 * and with it ordering, unread state and the sending rules, stay what they were.
 */
@Component
@RequiredArgsConstructor
public class ConversationViews {

    private final ConversationMapper mapper;
    private final MessageRepository messageRepository;
    private final ChatUserPrefsRepository prefsRepository;
    private final BlockClient blockClient;

    public ConversationResponse of(Conversation conversation, Long viewerId) {
        return of(conversation, viewerId, prefsOf(viewerId),
                blockClient.statesOf(viewerId, others(List.of(conversation), viewerId)));
    }

    /**
     * The viewer's inbox from stored conversations: what they deleted and not heard from since is left
     * out, and the rest is in {@link #ORDER}. One read of their choices serves the whole list.
     */
    public List<ConversationResponse> inbox(List<Conversation> conversations, Long viewerId) {
        ChatUserPrefs prefs = prefsOf(viewerId);
        Map<Long, BlockClient.BlockState> states = blockClient.statesOf(viewerId, others(conversations, viewerId));
        return conversations.stream()
                .map(conversation -> of(conversation, viewerId, prefs, states))
                .filter(view -> !view.inboxHidden())
                .sorted(ORDER)
                .toList();
    }

    /**
     * The one ordering of a person's conversations, wherever it is applied (the REST list here, the
     * client's own list when it is updated live): pinned first, the latest pin on top; then by newest
     * message; an id breaks every tie, so two lists built from the same data never differ.
     */
    public static final Comparator<ConversationResponse> ORDER = Comparator
            .comparing(ConversationResponse::pinnedAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(ConversationResponse::lastMessageAt, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(ConversationResponse::id, Comparator.reverseOrder());

    private ChatUserPrefs prefsOf(Long viewerId) {
        return prefsRepository.findById(viewerId).orElseGet(() -> ChatUserPrefs.empty(viewerId));
    }

    private List<Long> others(List<Conversation> conversations, Long viewerId) {
        return conversations.stream().flatMap(c -> c.getParticipantIds().stream())
                .filter(id -> !id.equals(viewerId)).distinct().toList();
    }

    private ConversationResponse of(Conversation conversation, Long viewerId, ChatUserPrefs prefs,
                                    Map<Long, BlockClient.BlockState> states) {
        ConversationResponse view = mapper.toResponse(conversation, viewerId);
        if (view == null) {
            return null;
        }
        var state = conversation.getParticipantIds().stream().filter(id -> !id.equals(viewerId))
                .findFirst().map(states::get).orElse(null);
        view = state == null ? view.withBlockState(null, null, null)
                : view.withBlockState(state.blockedByViewer(), state.blockedByViewer() || state.blockedViewer(), state.version());
        String id = conversation.getId();
        Instant cleared = prefs.clearedAt(id);
        boolean nothingVisible = cleared != null
                && (conversation.getLastMessageAt() == null || !conversation.getLastMessageAt().isAfter(cleared));
        Instant now = Instant.now();
        view = view.withViewerChoices(prefs.pinnedAt(id), prefs.muteModeAt(id, now), prefs.mutedUntilAt(id, now),
                nothingVisible);

        List<Long> hiders = conversation.getPreviewHiddenBy();
        boolean hidMessages = hiders != null && hiders.contains(viewerId);
        if (nothingVisible) {
            return view.withPreview(null, null, null, false);
        }
        if (!hidMessages && cleared == null) {
            return view;
        }
        List<Message> newest = messageRepository.findPage(id, viewerId, cleared, null, null, 1);
        if (newest.isEmpty()) {
            return view.withPreview(null, null, null, false);
        }
        Message latest = newest.get(0);
        // A video's caption is not previewed: it would outlive a recall or a hide done on the video.
        return view.withPreview(latest.isRecalled() || latest.getType() == MessageType.VIDEO ? null : latest.getContent(), latest.getType(),
                latest.getSenderId(), latest.isRecalled());
    }
}
