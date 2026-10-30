package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.*;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.request.StoryReplyRequest;
import com.tiktok.chatservice.dto.response.*;
import com.tiktok.chatservice.entity.*;
import com.tiktok.chatservice.exception.*;
import com.tiktok.chatservice.mapper.MessageMapperImpl;
import com.tiktok.chatservice.repository.*;
import com.tiktok.chatservice.sticker.StickerCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessageStorySendTest {
    private final ConversationService conversations = mock(ConversationService.class);
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final MessageRepository messages = mock(MessageRepository.class);
    private final StoryClient stories = mock(StoryClient.class);
    private final ChatEventPublisher events = mock(ChatEventPublisher.class);
    private final SimpMessagingTemplate topic = mock(SimpMessagingTemplate.class);
    private final MessageServiceImpl service = new MessageServiceImpl(conversations, conversationRepository, messages,
            new MessageMapperImpl(), topic, mock(BlockClient.class), events,
            StickerCatalog.load("http://localhost:9000", "video-media"), mock(ChatUserPrefsRepository.class),
            mock(VideoClient.class), stories, mock(ShareStatsClient.class));
    private static final StoryCard CARD = new StoryCard("s1", "AVAILABLE", 2L, "IMAGE", "https://signed/s1");
    private Conversation thread;

    @BeforeEach
    void setup() {
        thread = Conversation.builder().id("c").participantIds(List.of(1L, 2L)).initiatorId(1L).build();
        when(conversations.requireParticipant(1L, "c")).thenAnswer(i -> thread);
        lenient().when(conversations.promoteIfMutual(any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(stories.viewable(anyLong(), anyCollection())).thenReturn(Map.of("s1", CARD));
        lenient().when(stories.hydrate(anyLong(), anyCollection())).thenReturn(Map.of("s1", CARD));
    }

    private MessageResponse send(String key) {
        return service.sendMessage("c", 1L, SendMessageRequest.storyReply("s1", "  nice  ", key));
    }

    @Test
    void textAndStoryAreOneMessageDeliveredOnlyInPrivateFrames() {
        MessageResponse response = send("k");
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages, times(1)).save(saved.capture());
        assertThat(saved.getValue().getType()).isEqualTo(MessageType.STORY_REPLY);
        assertThat(saved.getValue().getContent()).isEqualTo("nice");
        assertThat(saved.getValue().getStoryId()).isEqualTo("s1");
        assertThat(response.story()).isEqualTo(CARD);
        verifyNoInteractions(topic);
        verify(events).toUser(eq(1L), any());
        verify(events).toUser(eq(2L), any());
    }

    @Test
    void storyTheSenderCannotSeeIs404AndNothingChanges() {
        when(stories.viewable(1L, List.of("s1"))).thenReturn(Map.of("s1", StoryCard.unavailable("s1")));
        assertThatThrownBy(() -> send("k")).isInstanceOf(StoryNotAvailableException.class)
                .extracting("status").isEqualTo(HttpStatus.NOT_FOUND);
        assertNoMutation();
    }

    @Test
    void storyOfSomeoneOutsideTheConversationIsRefused() {
        when(stories.viewable(1L, List.of("s1")))
                .thenReturn(Map.of("s1", new StoryCard("s1", "AVAILABLE", 99L, "IMAGE", "u")));
        assertThatThrownBy(() -> send("k")).isInstanceOf(StoryReplyRecipientException.class)
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertNoMutation();
    }

    @Test
    void checkOutageIs503AndNothingIsSent() {
        when(stories.viewable(1L, List.of("s1"))).thenThrow(new StoryCheckUnavailableException());
        assertThatThrownBy(() -> send("k")).isInstanceOf(StoryCheckUnavailableException.class)
                .extracting("code", "status").containsExactly("STORY_CHECK_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
        assertNoMutation();
    }

    @Test
    void blankAndOverlongTextAreRefusedBeforeAnyStoryCall() {
        assertThatThrownBy(() -> service.sendMessage("c", 1L, SendMessageRequest.storyReply("s1", "   \n ", "k")))
                .isInstanceOf(com.tiktok.common.exception.BadRequestException.class);
        assertThatThrownBy(() -> service.sendMessage("c", 1L, SendMessageRequest.storyReply("s1", "x".repeat(1001), "k")))
                .isInstanceOf(com.tiktok.common.exception.BadRequestException.class);
        verifyNoInteractions(stories);
        assertNoMutation();
    }

    @Test
    void storyIdOnAPlainTextMessageIsRefused() {
        var request = new SendMessageRequest(MessageType.TEXT, "hi", null, null, null, null, "s1");
        assertThatThrownBy(() -> service.sendMessage("c", 1L, request)).isInstanceOf(InvalidMessageException.class);
        assertNoMutation();
    }

    private void assertNoMutation() {
        verify(messages, never()).save(any());
        verify(conversations, never()).reopenForSend(any(), any());
        verifyNoInteractions(conversationRepository, topic, events);
    }

    @Test
    void retryWithSameKeyConvergesOnTheStoredMessageWithoutDelivery() {
        send("k");
        ArgumentCaptor<Message> saved = ArgumentCaptor.forClass(Message.class);
        verify(messages).save(saved.capture());
        clearInvocations(events, messages);
        when(messages.findByConversationIdAndSenderIdAndClientRequestId("c", 1L, "k"))
                .thenReturn(Optional.of(saved.getValue()));
        MessageResponse retried = send("k");
        assertThat(retried.id()).isEqualTo(saved.getValue().getId());
        assertThat(retried.replayed()).isTrue();
        verify(messages, never()).save(any());
        verifyNoInteractions(events, topic);
    }

    @Test
    void uniqueIndexRaceReturnsTheExistingMessage() {
        Message existing = Message.builder().id("existing").conversationId("c").senderId(1L)
                .type(MessageType.STORY_REPLY).storyId("s1").content("nice").sentAt(Instant.now()).build();
        when(messages.findByConversationIdAndSenderIdAndClientRequestId("c", 1L, "k"))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(messages.save(any())).thenThrow(new DuplicateKeyException("message_client_request_idx"));
        assertThat(send("k").id()).isEqualTo("existing");
        verifyNoInteractions(events, topic, conversationRepository);
    }

    @Test
    void recipientFrameIsRenderedForTheRecipientAndLosesTheUrlWhenAccessIsGone() {
        when(stories.hydrate(eq(2L), anyCollection())).thenReturn(Map.of("s1", StoryCard.unavailable("s1")));
        assertThat(send(null).story().state()).isEqualTo("AVAILABLE");
        ArgumentCaptor<ChatEvent> frame = ArgumentCaptor.forClass(ChatEvent.class);
        verify(events).toUser(eq(2L), frame.capture());
        assertThat(frame.getValue().message().story().state()).isEqualTo("UNAVAILABLE");
        assertThat(frame.getValue().message().story().mediaUrl()).isNull();
        assertThat(frame.getValue().message().content()).isEqualTo("nice");
    }

    @Test
    void historyBatchesAllStoryIdsInOneCallAndKeepsTextWhenTheStoryIsGone() {
        Message a = reply("a", "s1");
        Message b = reply("b", "s2");
        when(messages.findPage("c", 1L, null, null, null, 21)).thenReturn(List.of(a, b));
        when(stories.hydrate(eq(1L), anyCollection()))
                .thenReturn(Map.of("s1", CARD, "s2", StoryCard.unavailable("s2")));
        MessagePageResponse page = service.listMessages("c", 1L, null, 20);
        ArgumentCaptor<Collection<String>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(stories, times(1)).hydrate(eq(1L), ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder("s1", "s2");
        assertThat(page.items().get(1).story().state()).isEqualTo("UNAVAILABLE");
        assertThat(page.items().get(1).content()).isEqualTo("text-b");
    }

    @Test
    void recalledReplyHasNoTextAndNoStory() {
        Message recalled = Message.builder().id("r").conversationId("c").senderId(1L).type(MessageType.STORY_REPLY)
                .storyId("s1").content("secret").recalledAt(Instant.now()).sentAt(Instant.now()).build();
        when(messages.findPage("c", 1L, null, null, null, 21)).thenReturn(List.of(recalled));
        MessageResponse item = service.listMessages("c", 1L, null, 20).items().get(0);
        assertThat(item.content()).isNull();
        assertThat(item.story()).isNull();
        verifyNoInteractions(stories);
    }

    @Test
    void messagesWithoutAStoryFieldStillRead() {
        Message old = Message.builder().id("o").conversationId("c").senderId(2L).content("old").sentAt(Instant.now()).build();
        when(messages.findPage("c", 1L, null, null, null, 21)).thenReturn(List.of(old));
        MessageResponse item = service.listMessages("c", 1L, null, 20).items().get(0);
        assertThat(item.type()).isEqualTo(MessageType.TEXT);
        assertThat(item.story()).isNull();
    }

    @Test
    void storyReplyServiceFindsTheOwnerItselfAndNeverTrustsAClientRecipient() {
        var conversationService = mock(ConversationService.class);
        var messageService = mock(MessageService.class);
        var replies = new StoryReplyService(stories, conversationService, messageService);
        var created = mock(ConversationResponse.class);
        when(created.id()).thenReturn("c");
        when(conversationService.getOrCreate(1L, 2L)).thenReturn(created);
        replies.reply(1L, new StoryReplyRequest("s1", "hello", "k"));
        verify(conversationService).getOrCreate(1L, 2L);
        ArgumentCaptor<SendMessageRequest> sent = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(messageService).sendMessage(eq("c"), eq(1L), sent.capture());
        assertThat(sent.getValue().storyId()).isEqualTo("s1");
        assertThat(sent.getValue().clientRequestId()).isEqualTo("k");
    }

    @Test
    void storyReplyServiceOpensNoConversationForAStoryTheSenderCannotSee() {
        var conversationService = mock(ConversationService.class);
        var replies = new StoryReplyService(stories, conversationService, mock(MessageService.class));
        when(stories.viewable(1L, List.of("s1"))).thenReturn(Map.of("s1", StoryCard.unavailable("s1")));
        assertThatThrownBy(() -> replies.reply(1L, new StoryReplyRequest("s1", "hello", "k")))
                .isInstanceOf(StoryNotAvailableException.class);
        verifyNoInteractions(conversationService);
    }

    @Test
    void replyingToYourOwnStoryNeverOpensASelfConversation() {
        when(stories.viewable(1L, List.of("s1"))).thenReturn(Map.of("s1", new StoryCard("s1", "AVAILABLE", 1L, "IMAGE", "u")));
        var conversationService = mock(ConversationService.class);
        when(conversationService.getOrCreate(1L, 1L)).thenThrow(new CannotMessageSelfException());
        var replies = new StoryReplyService(stories, conversationService, mock(MessageService.class));
        assertThatThrownBy(() -> replies.reply(1L, new StoryReplyRequest("s1", "hello", "k")))
                .isInstanceOf(CannotMessageSelfException.class);
    }

    private static Message reply(String id, String storyId) {
        return Message.builder().id(id).conversationId("c").senderId(2L).type(MessageType.STORY_REPLY)
                .storyId(storyId).content("text-" + id).sentAt(Instant.now()).build();
    }
}
