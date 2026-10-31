package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.response.ImageView;
import com.tiktok.chatservice.entity.ChatImage;
import com.tiktok.chatservice.entity.ChatUserPrefs;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageRequestState;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.exception.InvalidImageException;
import com.tiktok.chatservice.exception.MessageNotFoundException;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.exception.MessageRequestNotAcceptedException;
import com.tiktok.chatservice.exception.MessagingBlockedException;
import com.tiktok.chatservice.exception.NotConversationParticipantException;
import com.tiktok.chatservice.repository.ChatImageRepository;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatImageServiceTest {

    private final ConversationService conversations = mock(ConversationService.class);
    private final ChatImageRepository images = mock(ChatImageRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final ChatUserPrefsRepository prefs = mock(ChatUserPrefsRepository.class);
    private final BlockClient blocks = mock(BlockClient.class);
    private final ChatImageService service = new ChatImageService(conversations, images, messages, prefs, blocks);
    private Conversation thread;

    @BeforeEach
    void setup() throws Exception {
        thread = Conversation.builder().id("c").participantIds(List.of(1L, 2L)).initiatorId(1L).build();
        lenient().when(conversations.requireParticipant(1L, "c")).thenAnswer(i -> thread);
        lenient().when(conversations.requireParticipant(2L, "c")).thenAnswer(i -> thread);
        lenient().when(conversations.requireParticipant(9L, "c")).thenThrow(new NotConversationParticipantException("c"));
        lenient().when(conversations.promoteIfMutual(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(images.save(any(ChatImage.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(prefs.findById(any())).thenReturn(Optional.empty());
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private static ChatImage stored(String messageId) {
        return ChatImage.builder().id("i1").conversationId("c").uploaderId(1L).messageId(messageId)
                .mime("image/png").width(20).height(10).size(5).data(new byte[]{1, 2, 3}).createdAt(Instant.now()).build();
    }

    private Message message(String id, boolean recalled, List<Long> hiddenFor) {
        return Message.builder().id(id).conversationId("c").senderId(1L).type(MessageType.IMAGE).imageId("i1")
                .sentAt(Instant.now()).recalledAt(recalled ? Instant.now() : null).hiddenFor(hiddenFor).build();
    }

    // --- upload

    @Test
    void uploadStoresTheServerDetectedTypeAndSizeForAnUnclaimedUploadThatExpires() throws Exception {
        ImageView view = service.upload("c", 1L, png());
        assertThat(view.mime()).isEqualTo("image/png");
        assertThat(view.width()).isEqualTo(20);
        assertThat(view.height()).isEqualTo(10);
        assertThat(view.url()).isEqualTo("/api/v1/conversations/c/images/" + view.imageId());
        var captor = org.mockito.ArgumentCaptor.forClass(ChatImage.class);
        verify(images).save(captor.capture());
        assertThat(captor.getValue().getUploaderId()).isEqualTo(1L);
        assertThat(captor.getValue().getMessageId()).isNull();
        assertThat(captor.getValue().getExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void uploadByANonParticipantIsRefusedAndStoresNothing() {
        assertThatThrownBy(() -> service.upload("c", 9L, new byte[]{1})).isInstanceOf(NotConversationParticipantException.class);
        verify(images, never()).save(any());
    }

    @Test
    void uploadIntoABlockedConversationIsRefused() {
        doThrow(new MessagingBlockedException()).when(blocks).requireNotBlocked(1L, 2L);
        assertThatThrownBy(() -> service.upload("c", 1L, new byte[]{1})).isInstanceOf(MessagingBlockedException.class);
        verify(images, never()).save(any());
    }

    @Test
    void uploadFollowsTheMessageRequestRules() throws Exception {
        thread = Conversation.builder().id("c").participantIds(List.of(1L, 2L)).initiatorId(2L)
                .requestState(MessageRequestState.PENDING).build();
        assertThatThrownBy(() -> service.upload("c", 1L, png())).isInstanceOf(MessageRequestNotAcceptedException.class);

        thread = Conversation.builder().id("c").participantIds(List.of(1L, 2L)).initiatorId(1L)
                .requestState(MessageRequestState.PENDING).lastMessageAt(Instant.now()).build();
        assertThatThrownBy(() -> service.upload("c", 1L, png())).isInstanceOf(MessageRequestLimitReachedException.class);
        verify(images, never()).save(any());
    }

    @Test
    void uploadRefusesAFileThatIsNotAPictureAndStoresNothing() {
        assertThatThrownBy(() -> service.upload("c", 1L, "<script>".getBytes())).isInstanceOf(InvalidImageException.class);
        verify(images, never()).save(any());
    }

    // --- view

    @Test
    void bothParticipantsMaySeeTheImageOfAVisibleMessage() {
        when(images.findInConversation("c", "i1")).thenReturn(Optional.of(stored("m1")));
        when(messages.findInConversation("c", "m1")).thenReturn(Optional.of(message("m1", false, null)));
        assertThat(service.open("c", 1L, "i1").getData()).hasSize(3);
        assertThat(service.open("c", 2L, "i1").getData()).hasSize(3);
    }

    @Test
    void aStrangerCannotSeeItEvenWithTheExactIds() {
        assertThatThrownBy(() -> service.open("c", 9L, "i1")).isInstanceOf(NotConversationParticipantException.class);
        verify(images, never()).findInConversation(any(), any());
    }

    @Test
    void anImageIdFromAnotherConversationIsNotFound() {
        when(images.findInConversation("c", "other")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.open("c", 1L, "other")).isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void anUnsentUploadIsVisibleToItsUploaderOnly() {
        when(images.findInConversation("c", "i1")).thenReturn(Optional.of(stored(null)));
        assertThat(service.open("c", 1L, "i1")).isNotNull();
        assertThatThrownBy(() -> service.open("c", 2L, "i1")).isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void aRecalledMessageNoLongerServesItsImage() {
        when(images.findInConversation("c", "i1")).thenReturn(Optional.of(stored("m1")));
        when(messages.findInConversation("c", "m1")).thenReturn(Optional.of(message("m1", true, null)));
        assertThatThrownBy(() -> service.open("c", 2L, "i1")).isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void aMessageDeletedFromOneSideStaysVisibleToTheOtherOnly() {
        when(images.findInConversation("c", "i1")).thenReturn(Optional.of(stored("m1")));
        when(messages.findInConversation("c", "m1")).thenReturn(Optional.of(message("m1", false, List.of(2L))));
        assertThatThrownBy(() -> service.open("c", 2L, "i1")).isInstanceOf(MessageNotFoundException.class);
        assertThat(service.open("c", 1L, "i1")).isNotNull();
    }

    @Test
    void aConversationDeletedFromMySideHidesWhatCameBeforeIt() {
        ChatUserPrefs cleared = mock(ChatUserPrefs.class);
        when(cleared.clearedAt("c")).thenReturn(Instant.now().plusSeconds(60));
        when(prefs.findById(2L)).thenReturn(Optional.of(cleared));
        when(images.findInConversation("c", "i1")).thenReturn(Optional.of(stored("m1")));
        when(messages.findInConversation("c", "m1")).thenReturn(Optional.of(message("m1", false, null)));
        assertThatThrownBy(() -> service.open("c", 2L, "i1")).isInstanceOf(MessageNotFoundException.class);
    }

    @Test
    void theImageMustBeTheOneTheMessageNames() {
        Message other = Message.builder().id("m1").conversationId("c").senderId(1L).type(MessageType.IMAGE)
                .imageId("different").sentAt(Instant.now()).build();
        when(images.findInConversation("c", "i1")).thenReturn(Optional.of(stored("m1")));
        when(messages.findInConversation("c", "m1")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> service.open("c", 1L, "i1")).isInstanceOf(MessageNotFoundException.class);
    }
}
