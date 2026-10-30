package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.response.ReceiptView;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.ReceiptMark;
import com.tiktok.chatservice.exception.InvalidReceiptException;
import com.tiktok.chatservice.mapper.MessageMapper;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.ConversationRepositoryCustom.MarkKind;
import com.tiktok.chatservice.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What an acknowledgement is allowed to prove. A delivery counts only across a run of messages that
 * are all named; a message that is not the caller's to receive is refused outright; reading is by
 * position; and the other side hears only when a mark moved.
 */
@ExtendWith(MockitoExtension.class)
class MessageAckTest {

    private static final Long A = 1L;
    private static final Long B = 2L;
    private static final Instant T0 = Instant.parse("2026-10-28T08:00:00Z");

    @Mock private ConversationService conversationService;
    @Mock private ConversationRepository conversationRepository;
    @Mock private MessageRepository messageRepository;
    @Mock private MessageMapper messageMapper;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private BlockClient blockClient;
    @Mock private ChatEventPublisher eventPublisher;
    @Mock private ChatUserPrefsRepository prefs;

    private MessageServiceImpl service;
    private Conversation conversation;

    @BeforeEach
    void setUp() {
        service = new MessageServiceImpl(conversationService, conversationRepository, messageRepository, messageMapper,
                messagingTemplate, blockClient, eventPublisher,
                com.tiktok.chatservice.sticker.StickerCatalog.load("http://localhost:9000", "video-media"), prefs,
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.VideoClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.StoryClient.class),
                org.mockito.Mockito.mock(com.tiktok.chatservice.client.ShareStatsClient.class));
        conversation = Conversation.builder().id("c1").participantIds(List.of(A, B)).participantKey("1_2")
                .deliveredMark(new HashMap<>()).readMark(new HashMap<>()).build();
        lenient().when(conversationService.requireParticipant(B, "c1")).thenReturn(conversation);
        lenient().when(conversationService.requireParticipant(A, "c1")).thenReturn(conversation);
        lenient().when(conversationRepository.findByIdAndDeletedAtIsNull("c1")).thenReturn(Optional.of(conversation));
    }

    private Message msg(String id, long second, Long sender) {
        return Message.builder().id(id).conversationId("c1").senderId(sender).sentAt(T0.plusSeconds(second)).build();
    }

    private Message hiddenFrom(String id, long second, Long sender, Long hider) {
        return Message.builder().id(id).conversationId("c1").senderId(sender).sentAt(T0.plusSeconds(second))
                .hiddenFor(List.of(hider)).build();
    }

    private void stored(Message... messages) {
        for (Message m : messages) {
            lenient().when(messageRepository.findInConversation("c1", m.getId())).thenReturn(Optional.of(m));
        }
        lenient().when(messageRepository.findInConversation(eq("c1"), org.mockito.ArgumentMatchers.<java.util.Collection<String>>any()))
                .thenAnswer(call -> {
                    java.util.Collection<String> wanted = call.getArgument(1);
                    return java.util.Arrays.stream(messages).filter(m -> wanted.contains(m.getId())).toList();
                });
    }

    private void stretch(Message... between) {
        lenient().when(messageRepository.findOthersBetween(eq("c1"), eq(B), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(between));
    }

    // ---- delivered

    @Test
    void aRunOfNamedMessagesAdvancesTheMarkToTheNewestOne() {
        Message m1 = msg("m1", 1, A), m2 = msg("m2", 2, A);
        stored(m1, m2);
        stretch(m2); // after m1, up to m2
        when(conversationRepository.advanceMark(eq("c1"), eq(B), eq(MarkKind.DELIVERED), eq("m2"), eq(m2.getSentAt()), any()))
                .thenReturn(true);

        service.markDelivered("c1", B, List.of("m1", "m2"));

        verify(eventPublisher).receipt(eq(conversation), any(ReceiptView.class));
    }

    @Test
    void anAcknowledgementThatLeavesAGapDoesNotCoverTheMessageItSkipped() {
        // The mark is at m0. The client names m3 only — m1 and m2 never reached it.
        Message m0 = msg("m0", 0, A), m1 = msg("m1", 1, A), m2 = msg("m2", 2, A), m3 = msg("m3", 3, A);
        conversation.getDeliveredMark().put("2", new ReceiptMark("m0", m0.getSentAt(), T0));
        stored(m3);
        stretch(m1, m2, m3);

        service.markDelivered("c1", B, List.of("m3"));

        verify(conversationRepository, never()).advanceMark(any(), any(), any(), any(), any(), any());
        verify(eventPublisher, never()).receipt(any(), any());
    }

    @Test
    void aGapStopsTheMarkAtTheLastMessageBeforeIt() {
        Message m0 = msg("m0", 0, A), m1 = msg("m1", 1, A), m2 = msg("m2", 2, A), m3 = msg("m3", 3, A);
        conversation.getDeliveredMark().put("2", new ReceiptMark("m0", m0.getSentAt(), T0));
        stored(m1, m3);
        stretch(m1, m2, m3);

        service.markDelivered("c1", B, List.of("m1", "m3"));

        verify(conversationRepository).advanceMark(eq("c1"), eq(B), eq(MarkKind.DELIVERED), eq("m1"), eq(m1.getSentAt()), any());
        verify(conversationRepository, never()).advanceMark(any(), any(), any(), eq("m3"), any(), any());
    }

    @Test
    void aMessageTheRecipientDeletedFromTheirSideDoesNotBlockTheRun() {
        Message m0 = msg("m0", 0, A), m1 = hiddenFrom("m1", 1, A, B), m2 = msg("m2", 2, A);
        conversation.getDeliveredMark().put("2", new ReceiptMark("m0", m0.getSentAt(), T0));
        stored(m2);
        stretch(m1, m2);

        service.markDelivered("c1", B, List.of("m2"));

        verify(conversationRepository).advanceMark(eq("c1"), eq(B), eq(MarkKind.DELIVERED), eq("m2"), any(), any());
    }

    @Test
    void theAcknowledgementIsBoundedByWhatItNames_aLaterMessageIsNeverCoveredByAnEarlierOne() {
        // m1 acknowledged while m2 was already stored: the stretch is asked for up to m1 only.
        Message m1 = msg("m1", 1, A);
        stored(m1);
        stretch();

        service.markDelivered("c1", B, List.of("m1"));

        ArgumentCaptor<String> through = ArgumentCaptor.forClass(String.class);
        verify(messageRepository).findOthersBetween(eq("c1"), eq(B), any(), any(), eq(m1.getSentAt()), through.capture(), anyInt());
        assertThat(through.getValue()).isEqualTo("m1");
        verify(conversationRepository).advanceMark(eq("c1"), eq(B), eq(MarkKind.DELIVERED), eq("m1"), any(), any());
        verify(conversationRepository, never()).advanceMark(any(), any(), any(), eq("m2"), any(), any());
    }

    @Test
    void aRepeatedAcknowledgementTellsNobody() {
        Message m1 = msg("m1", 1, A);
        stored(m1);
        stretch(m1);
        when(conversationRepository.advanceMark(any(), any(), any(), any(), any(), any())).thenReturn(false);

        service.markDelivered("c1", B, List.of("m1"));

        verify(eventPublisher, never()).receipt(any(), any());
    }

    @Test
    void refusesWhatTheCallerCannotHaveReceived() {
        Message mine = msg("mine", 1, B);
        stored(mine);

        assertThatThrownBy(() -> service.markDelivered("c1", B, List.of("mine"))).isInstanceOf(InvalidReceiptException.class);
        assertThatThrownBy(() -> service.markDelivered("c1", B, List.of("elsewhere"))).isInstanceOf(InvalidReceiptException.class);
        assertThatThrownBy(() -> service.markDelivered("c1", B, List.of())).isInstanceOf(InvalidReceiptException.class);
        assertThatThrownBy(() -> service.markDelivered("c1", B, java.util.stream.IntStream.range(0, 101).mapToObj(i -> "m" + i).toList()))
                .isInstanceOf(InvalidReceiptException.class);
        verify(conversationRepository, never()).advanceMark(any(), any(), any(), any(), any(), any());
    }

    // ---- read

    @Test
    void readingByPositionMovesBothMarksToThatMessageAndTellsTheSender() {
        Message m1 = msg("m1", 1, A);
        stored(m1);
        when(conversationRepository.advanceMark(eq("c1"), eq(B), any(), eq("m1"), any(), any())).thenReturn(true);

        service.markRead("c1", B, "m1");

        verify(conversationRepository).advanceMark(eq("c1"), eq(B), eq(MarkKind.READ), eq("m1"), eq(m1.getSentAt()), any());
        verify(conversationRepository).advanceMark(eq("c1"), eq(B), eq(MarkKind.DELIVERED), eq("m1"), eq(m1.getSentAt()), any());
        verify(eventPublisher).receipt(eq(conversation), any(ReceiptView.class));
    }

    @Test
    void readingWhatWasAlreadyReadTellsNobody() {
        Message m1 = msg("m1", 1, A);
        stored(m1);
        when(conversationRepository.advanceMark(any(), any(), any(), any(), any(), any())).thenReturn(false);

        service.markRead("c1", B, "m1");

        verify(eventPublisher, never()).receipt(any(), any());
    }

    @Test
    void youCannotReadYourOwnMessageOrOneYouDeletedOrOneThatIsNotThere() {
        Message mine = msg("mine", 1, B);
        Message hidden = hiddenFrom("hidden", 2, A, B);
        stored(mine, hidden);

        assertThatThrownBy(() -> service.markRead("c1", B, "mine")).isInstanceOf(InvalidReceiptException.class);
        assertThatThrownBy(() -> service.markRead("c1", B, "hidden")).isInstanceOf(InvalidReceiptException.class);
        assertThatThrownBy(() -> service.markRead("c1", B, "nope")).isInstanceOf(InvalidReceiptException.class);
        verify(conversationRepository, never()).advanceMark(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aClientWithoutPositionsReadsThroughTheNewestOfTheOtherSide() {
        Message newest = msg("m9", 9, A);
        when(messageRepository.findNewestOthers("c1", B)).thenReturn(Optional.of(newest));

        service.markRead("c1", B, null);

        verify(conversationRepository).advanceMark(eq("c1"), eq(B), eq(MarkKind.READ), eq("m9"), any(), any());
    }

    // ---- receipts

    @Test
    void receiptsAreTheOtherParticipantsMarks() {
        ReceiptMark delivered = new ReceiptMark("m2", T0.plusSeconds(2), T0.plusSeconds(5));
        ReceiptMark read = new ReceiptMark("m1", T0.plusSeconds(1), T0.plusSeconds(9));
        conversation.getDeliveredMark().put("2", delivered);
        conversation.getDeliveredMark().put("1", new ReceiptMark("x", T0, T0));
        conversation.getReadMark().put("2", read);

        ReceiptView view = service.receipts("c1", A);

        assertThat(view.userId()).isEqualTo(B);
        assertThat(view.delivered()).isEqualTo(delivered);
        assertThat(view.read()).isEqualTo(read);
    }
}
