package com.tiktok.chatservice.mapper;

import com.tiktok.chatservice.entity.Conversation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** "Unread" is the other side having sent something the viewer has not caught up on — not the last message being theirs. */
class ConversationUnreadTest {

    private static final Long ME = 1L;
    private static final Long PEER = 2L;
    private static final Instant T0 = Instant.parse("2026-10-28T08:00:00Z");

    private final ConversationMapper mapper = new ConversationMapperImpl();

    private Conversation conversation(Long lastSender, Instant lastAt, Map<String, Instant> sentAt, Map<String, Instant> readAt) {
        return Conversation.builder().id("c1").participantIds(List.of(ME, PEER)).participantKey("1_2")
                .lastMessageSenderId(lastSender).lastMessageAt(lastAt)
                .lastSentAt(new HashMap<>(sentAt)).lastReadAt(new HashMap<>(readAt)).build();
    }

    private boolean unreadFor(Conversation c, Long viewer) {
        return mapper.toResponse(c, viewer).hasUnread();
    }

    @Test
    void aMessageFromTheOtherSideIsUnreadUntilItIsRead() {
        Conversation c = conversation(PEER, T0, Map.of("2", T0), Map.of());
        assertThat(unreadFor(c, ME)).isTrue();

        Conversation read = conversation(PEER, T0, Map.of("2", T0), Map.of("1", T0));
        assertThat(unreadFor(read, ME)).isFalse();
    }

    @Test
    void replyingWithoutOpeningTheThreadDoesNotMakeWhatCameBeforeRead() {
        // The peer wrote at T0; the viewer replied at T0+5 and never read. The last message is the viewer's.
        Conversation c = conversation(ME, T0.plusSeconds(5), Map.of("2", T0, "1", T0.plusSeconds(5)), Map.of());
        assertThat(unreadFor(c, ME)).isTrue();
        // The peer, on the other hand, has nothing unread from the viewer's side until they read it too.
        assertThat(unreadFor(c, PEER)).isTrue();
    }

    @Test
    void readingUpToAnEarlierMessageLeavesALaterOneUnread() {
        Conversation c = conversation(PEER, T0.plusSeconds(10), Map.of("2", T0.plusSeconds(10)), Map.of("1", T0.plusSeconds(3)));
        assertThat(unreadFor(c, ME)).isTrue();
    }

    @Test
    void yourOwnLastMessageIsNeverUnreadForYouOnAConversationWithNothingFromTheOtherSide() {
        Conversation c = conversation(ME, T0, Map.of("1", T0), Map.of());
        assertThat(unreadFor(c, ME)).isFalse();
        assertThat(unreadFor(c, PEER)).isTrue();
    }

    @Test
    void conversationsFromBeforePerSenderTimesFallBackToTheLastMessage() {
        Conversation theirs = conversation(PEER, T0, Map.of(), Map.of());
        assertThat(unreadFor(theirs, ME)).isTrue();

        Conversation mine = conversation(ME, T0, Map.of(), Map.of());
        assertThat(unreadFor(mine, ME)).isFalse();

        Conversation read = conversation(PEER, T0, Map.of(), Map.of("1", T0.plusSeconds(1)));
        assertThat(unreadFor(read, ME)).isFalse();
    }

    @Test
    void anEmptyConversationHasNothingUnread() {
        Conversation c = conversation(null, null, Map.of(), Map.of());
        assertThat(unreadFor(c, ME)).isFalse();
    }
}
