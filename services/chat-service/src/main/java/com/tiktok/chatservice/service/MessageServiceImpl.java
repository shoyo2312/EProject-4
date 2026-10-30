package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.client.ShareStatsClient;
import com.tiktok.chatservice.client.StoryClient;
import com.tiktok.chatservice.client.VideoClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.ChatEvent;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.dto.response.MessageUpdateResponse;
import com.tiktok.chatservice.dto.response.ReactionView;
import com.tiktok.chatservice.dto.response.ReceiptView;
import com.tiktok.chatservice.dto.response.ReplyQuote;
import com.tiktok.chatservice.dto.response.SendPermission;
import com.tiktok.chatservice.dto.response.StickerView;
import com.tiktok.chatservice.dto.response.StoryCard;
import com.tiktok.chatservice.dto.response.VideoCard;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.entity.ReceiptMark;
import com.tiktok.chatservice.exception.ConversationNotFoundException;
import com.tiktok.chatservice.exception.InvalidReactionException;
import com.tiktok.chatservice.exception.InvalidReceiptException;
import com.tiktok.chatservice.repository.ConversationRepositoryCustom;
import com.tiktok.chatservice.exception.InvalidReplyTargetException;
import com.tiktok.chatservice.exception.MessageNotFoundException;
import com.tiktok.chatservice.exception.MessageRecalledException;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.exception.MessageRequestNotAcceptedException;
import com.tiktok.chatservice.exception.NotMessageAuthorException;
import com.tiktok.chatservice.exception.ReactionNotAllowedException;
import com.tiktok.chatservice.exception.StoryNotAvailableException;
import com.tiktok.chatservice.exception.StoryReplyRecipientException;
import com.tiktok.chatservice.exception.VideoNotAvailableException;
import com.tiktok.chatservice.exception.VideoNotShareableToRecipientException;
import com.tiktok.chatservice.mapper.MessageMapper;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import com.tiktok.chatservice.sticker.StickerCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Persists to Mongo first, then broadcasts over STOMP — both the REST send endpoint and the
 * WebSocket @MessageMapping entry point funnel through here, so a message is only ever
 * delivered in real time after it is durably stored.
 *
 * <p>Reactions, recalls and deleting-for-me live here too, because they are all "a participant does
 * something to a stored message" and share its lookups. Each is one conditional update on the
 * message document (see {@code MessageRepositoryCustom}); none touches {@code lastMessageAt},
 * {@code requestMessage} or the sending rules, so none can hand anyone a new turn.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MessageServiceImpl implements MessageService {

    private static final String TOPIC_PREFIX = "/topic/conversations/";

    /** The six reactions the client offers. Anything else is refused rather than stored. */
    static final Set<String> REACTIONS = Set.of("\u2764\uFE0F", "\uD83D\uDE02", "\uD83D\uDE2E",
            "\uD83D\uDE22", "\uD83D\uDE21", "\uD83D\uDC4D");

    /** How much of an original a quote carries; the client clamps it further to its own width. */
    static final int QUOTE_MAX = 140;

    private final ConversationService conversationService;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;
    private final MessageMapper messageMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final BlockClient blockClient;
    private final ChatEventPublisher eventPublisher;
    private final StickerCatalog stickerCatalog;
    private final ChatUserPrefsRepository prefsRepository;
    private final VideoClient videoClient;
    private final StoryClient storyClient;
    private final ShareStatsClient shareStatsClient;

    @Override
    public MessageResponse sendMessage(String conversationId, Long senderId, SendMessageRequest request) {
        Conversation conversation = conversationService.requireParticipant(senderId, conversationId);
        // A retry is the already-delivered message, even when the pending-request policy now
        // says REQUEST_SENT. It does not promote, reopen, broadcast, or spend another slot.
        if (request.clientRequestId() != null) {
            Message existing = deduplicated(conversationId, senderId, request.clientRequestId());
            if (existing != null) {
                return render(existing, senderId).asReplay();
            }
        }
        // Per message, not only when the conversation opens: a block placed later has to stop a
        // thread that already exists.
        requireNoBlock(conversation, senderId);

        // Before anything below can change state: a payload that is wrong must leave the conversation,
        // its request and the sender's one message exactly as they were.
        MessagePayload payload = MessagePayload.from(request, stickerCatalog);
        String replyTo = requireReplyTarget(conversationId, senderId, request.replyToMessageId());

        if (payload.type() == MessageType.VIDEO) {
            requireViewable(payload.videoId(), senderId, true);
            for (Long participant : conversation.getParticipantIds()) {
                if (!participant.equals(senderId)) {
                    requireViewable(payload.videoId(), participant, false);
                }
            }
        }

        if (payload.type() == MessageType.STORY_REPLY) {
            requireStoryOfOtherParticipant(payload.storyId(), conversation, senderId);
        }

        // A request whose two sides follow each other by now is just a conversation.
        conversation = conversationService.promoteIfMutual(conversation);

        Instant now = Instant.now();

        // The server decides, and every entry point — REST, STOMP — gets the same answer from here.
        boolean requestMessage = false;
        boolean retry = false;
        switch (SendPolicy.of(conversation, senderId, now)) {
            case ALLOWED -> {
            }
            case FIRST_MESSAGE_ONLY -> {
                requestMessage = true;
                retry = conversation.getLastMessageAt() != null;
            }
            // Deleting a request and then writing to the person is reaching out: the thread opens.
            case REOPEN_ON_SEND -> conversation = conversationService.reopenForSend(conversation, senderId);
            case REQUEST_SENT -> throw new MessageRequestLimitReachedException();
            case AWAITING_ACCEPT -> throw new MessageRequestNotAcceptedException();
        }
        if (retry) {
            // A new round: the finished one stops holding the unique slot. Only messages older than
            // the waiting period are cleared, so racing retries cannot clear each other's message.
            messageRepository.clearStaleRequestFlag(conversationId, now.minus(SendPolicy.RETRY_AFTER));
        }

        Message message = Message.builder()
                .id(Message.newId())
                .conversationId(conversationId)
                .senderId(senderId)
                .type(payload.type())
                .content(payload.content())
                .stickerId(payload.stickerId())
                .videoId(payload.videoId())
                .storyId(payload.storyId())
                .clientRequestId(request.clientRequestId())
                .sentAt(now)
                .requestMessage(requestMessage ? Boolean.TRUE : null)
                .replyToMessageId(replyTo)
                .build();
        try {
            messageRepository.save(message);
        } catch (DuplicateKeyException e) {
            if (request.clientRequestId() != null) {
                Message existing = deduplicated(conversationId, senderId, request.clientRequestId());
                if (existing != null) {
                    return render(existing, senderId).asReplay();
                }
            }
            // one_request_message_idx: another send already used this request's single message —
            // a second tab, or the same send arriving over the other transport. Nothing was stored.
            throw new MessageRequestLimitReachedException();
        }

        if (retry && conversation.isDeclined()) {
            // The retry puts a deleted request back in the recipient's box.
            conversationRepository.rependRequest(conversationId, senderId, now);
        }

        // A field-level update rather than save(conversation): the other participant writes this
        // same document on every send and read, and a whole-document save under @Version lost
        // that race after the message was already stored. See ConversationRepositoryCustom.
        conversationRepository.recordMessage(conversationId, senderId, payload.type(), payload.content(), now);

        MessageResponse response = render(message, senderId);
        if (replyTo == null && payload.type() != MessageType.VIDEO && payload.type() != MessageType.STORY_REPLY) {
            messagingTemplate.convertAndSend(TOPIC_PREFIX + conversationId, response);
            for (Long participant : conversation.getParticipantIds()) {
                if (!participant.equals(senderId)) {
                    eventPublisher.toUser(participant, ChatEvent.message(response));
                }
            }
        } else {
            // Video cards and reply quotes depend on who is looking — the other side may have deleted the
            // original from their own — so it is rendered per person and goes to each of them
            // privately, the sender's other tabs included, instead of one frame on the shared topic.
            for (Long participant : conversation.getParticipantIds()) {
                eventPublisher.toUser(participant, ChatEvent.message(participant.equals(senderId) ? response : render(message, participant)));
            }
        }
        if (requestMessage) {
            // The recipient learns of the request without knowing its id, and the sender's other
            // tabs lock their composer — both from the stored state, not from anything the client said.
            conversationRepository.findByIdAndDeletedAtIsNull(conversationId)
                    .ifPresent(eventPublisher::conversationChanged);
        }

        if (payload.type() == MessageType.VIDEO) {
            try {
                shareStatsClient.count(payload.videoId(), senderId, message.getId());
            } catch (RuntimeException e) {
                log.warn("Could not count delivered chat share {}", message.getId(), e);
            }
        }
        return response;
    }

    private Message deduplicated(String conversationId, Long senderId, String key) {
        return messageRepository.findByConversationIdAndSenderIdAndClientRequestId(conversationId, senderId, key)
                .orElse(null);
    }

    /**
     * The sender must be able to watch the story right now, and its owner must be the one other
     * person in this conversation: the client names a story and a conversation, never a recipient.
     * If story-service cannot answer, nothing is sent.
     */
    private void requireStoryOfOtherParticipant(String storyId, Conversation conversation, Long senderId) {
        StoryCard card = storyClient.viewable(senderId, List.of(storyId)).get(storyId);
        if (card == null || !"AVAILABLE".equals(card.state()) || card.ownerId() == null) {
            throw new StoryNotAvailableException();
        }
        List<Long> others = conversation.getParticipantIds().stream().filter(id -> !id.equals(senderId)).toList();
        if (others.size() != 1 || !others.get(0).equals(card.ownerId())) {
            throw new StoryReplyRecipientException();
        }
    }

    private void requireViewable(String videoId, Long viewerId, boolean sender) {
        VideoCard card = videoClient.viewable(viewerId, List.of(videoId)).get(videoId);
        if (card == null || !"AVAILABLE".equals(card.state())) {
            if (sender) {
                throw new VideoNotAvailableException();
            }
            throw new VideoNotShareableToRecipientException();
        }
    }

    @Override
    public MessagePageResponse listMessages(String conversationId, Long currentUserId, String cursor, int size) {
        conversationService.requireParticipant(currentUserId, conversationId);
        return page(conversationId, currentUserId, cursor, size);
    }

    @Override
    public MessagePageResponse exportMessages(String conversationId, Long viewerId, String cursor, int size) {
        // The internal caller acts for the person whose data this is, so the same rules hold: they
        // must be in the conversation, and what they deleted from their side stays out.
        conversationService.requireParticipant(viewerId, conversationId);
        return page(conversationId, viewerId, cursor, size);
    }

    private MessagePageResponse page(String conversationId, Long viewerId, String cursor, int size) {
        MessageCursor before = MessageCursor.decode(cursor);
        Instant cleared = clearedAt(viewerId, conversationId);
        List<Message> fetched = before == null
                ? messageRepository.findPage(conversationId, viewerId, cleared, null, null, size + 1)
                : messageRepository.findPage(conversationId, viewerId, cleared, before.sentAt(), before.id(), size + 1);

        boolean hasMore = fetched.size() > size;
        List<Message> page = hasMore ? fetched.subList(0, size) : fetched;

        String nextCursor = hasMore ? MessageCursor.of(page.get(page.size() - 1)).encode() : null;

        // One query for every original a page of replies points at, not one per quote.
        Set<String> originalIds = new HashSet<>();
        for (Message message : page) {
            if (message.getReplyToMessageId() != null) {
                originalIds.add(message.getReplyToMessageId());
            }
        }
        Map<String, Message> originals = new HashMap<>();
        for (Message original : messageRepository.findInConversation(conversationId, originalIds)) {
            originals.put(original.getId(), original);
        }

        Map<String, VideoCard> videos = hydrate(page, originals.values(), viewerId, cleared);
        Map<String, StoryCard> stories = hydrateStories(page, viewerId, cleared);
        return new MessagePageResponse(page.stream().map(m -> render(m, viewerId, cleared, originals, videos, stories)).toList(), nextCursor, hasMore);
    }

    /** A stored message as the viewer draws it: sticker resolved, reactions listed, a recall redacted, the quote worked out. */
    private MessageResponse render(Message message, Long viewerId) {
        Map<String, Message> originals = new HashMap<>();
        String replyTo = message.getReplyToMessageId();
        if (replyTo != null) {
            messageRepository.findInConversation(message.getConversationId(), replyTo)
                    .ifPresent(original -> originals.put(original.getId(), original));
        }
        Instant cleared = clearedAt(viewerId, message.getConversationId());
        return render(message, viewerId, cleared, originals,
                hydrate(List.of(message), originals.values(), viewerId, cleared),
                hydrateStories(List.of(message), viewerId, cleared));
    }

    private Map<String, VideoCard> hydrate(Collection<Message> messages,
                                            Collection<Message> originals, Long viewerId, Instant cleared) {
        Set<String> ids = new HashSet<>();
        Stream.concat(messages.stream(), originals.stream())
                .filter(m -> !m.isRecalled() && !hiddenFrom(m, viewerId, cleared))
                .filter(m -> m.getType() == MessageType.VIDEO && m.getVideoId() != null)
                .forEach(m -> ids.add(m.getVideoId()));
        return ids.isEmpty() ? Map.of() : videoClient.hydrate(viewerId, ids);
    }

    private Map<String, StoryCard> hydrateStories(Collection<Message> messages, Long viewerId, Instant cleared) {
        Set<String> ids = new HashSet<>();
        messages.stream()
                .filter(m -> !m.isRecalled() && !hiddenFrom(m, viewerId, cleared))
                .filter(m -> m.getType() == MessageType.STORY_REPLY && m.getStoryId() != null)
                .forEach(m -> ids.add(m.getStoryId()));
        return ids.isEmpty() ? Map.of() : storyClient.hydrate(viewerId, ids);
    }

    private MessageResponse render(Message message, Long viewerId, Instant cleared, Map<String, Message> originals,
                                   Map<String, VideoCard> videos, Map<String, StoryCard> stories) {
        MessageResponse response = messageMapper.toResponse(message);
        if (response == null) {
            return null;
        }
        if (message.isRecalled()) {
            response = response.redacted();
        } else {
            if (response.type() == MessageType.STICKER) {
                response = response.withSticker(stickerCatalog.find(response.stickerId()).orElse(null));
            }
            if (response.type() == MessageType.VIDEO) {
                response = response.withVideo(videos.getOrDefault(message.getVideoId(), VideoCard.unavailable(message.getVideoId())));
            }
            if (response.type() == MessageType.STORY_REPLY) {
                response = response.withStory(stories.getOrDefault(message.getStoryId(), StoryCard.unavailable(message.getStoryId())));
            }
            response = response.withReactions(reactionViews(message));
        }
        if (message.getReplyToMessageId() != null) {
            response = response.withReplyTo(quote(message.getReplyToMessageId(), originals.get(message.getReplyToMessageId()), viewerId, cleared, videos));
        }
        return response;
    }

    /** What the viewer may see of the original right now: recalled and deleted-for-them carry no content. */
    private ReplyQuote quote(String id, Message original, Long viewerId, Instant cleared, Map<String, VideoCard> videos) {
        if (original == null || hiddenFrom(original, viewerId, cleared)) {
            return ReplyQuote.unavailable(id);
        }
        if (original.isRecalled()) {
            return ReplyQuote.recalled(id, original.getSenderId());
        }
        StickerView sticker = original.getType() == MessageType.STICKER
                ? stickerCatalog.find(original.getStickerId()).orElse(null) : null;
        return new ReplyQuote(id, ReplyQuote.State.OK, original.getSenderId(), original.getType(),
                shorten(original.getContent()), sticker,
                original.getType() == MessageType.VIDEO
                        ? videos.getOrDefault(original.getVideoId(), VideoCard.unavailable(original.getVideoId())).minimal() : null);
    }

    private static String shorten(String text) {
        if (text == null || text.length() <= QUOTE_MAX) {
            return text;
        }
        int end = QUOTE_MAX;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "\u2026";
    }

    private static List<ReactionView> reactionViews(Message message) {
        if (message.getReactions() == null || message.getReactions().isEmpty()) {
            return List.of();
        }
        return message.getReactions().entrySet().stream()
                .map(entry -> new ReactionView(Long.valueOf(entry.getKey()), entry.getValue()))
                .sorted(Comparator.comparing(ReactionView::userId))
                .toList();
    }

    private static MessageUpdateResponse updateOf(Message message) {
        return new MessageUpdateResponse(message.getId(), message.getConversationId(), message.getRevision(),
                message.getRecalledAt(), message.isRecalled() ? List.of() : reactionViews(message));
    }

    private void requireNoBlock(Conversation conversation, Long userId) {
        for (Long participant : conversation.getParticipantIds()) {
            if (!participant.equals(userId)) {
                blockClient.requireNotBlocked(userId, participant);
            }
        }
    }

    /**
     * The original must be in this conversation, not recalled, and not deleted from the sender's own
     * side — checked against storage, whatever the client believes it is looking at. Null when the
     * request is not a reply.
     */
    private String requireReplyTarget(String conversationId, Long senderId, String replyToMessageId) {
        if (replyToMessageId == null) {
            return null;
        }
        Message original = replyToMessageId.isBlank() ? null
                : messageRepository.findInConversation(conversationId, replyToMessageId).orElse(null);
        if (original == null || original.isRecalled()
                || hiddenFrom(original, senderId, clearedAt(senderId, conversationId))) {
            throw new InvalidReplyTargetException();
        }
        return original.getId();
    }

    @Override
    public MessageUpdateResponse react(String conversationId, Long userId, String messageId, String emoji) {
        Conversation conversation = conversationService.requireParticipant(userId, conversationId);
        String reaction = canonical(emoji);
        if (!REACTIONS.contains(reaction)) {
            throw new InvalidReactionException();
        }
        // A reaction is an interaction: it follows the sending rules, so it cannot be a way round a
        // block, a request nobody has accepted yet, or a request the viewer deleted.
        requireNoBlock(conversation, userId);
        conversation = conversationService.promoteIfMutual(conversation);
        if (SendPolicy.of(conversation, userId, Instant.now()) != SendPermission.ALLOWED) {
            throw new ReactionNotAllowedException();
        }

        Instant cleared = clearedAt(userId, conversationId);
        Message message = messageRepository.findInConversation(conversationId, messageId)
                .filter(found -> !hiddenFrom(found, userId, cleared))
                .orElseThrow(MessageNotFoundException::new);
        if (message.isRecalled()) {
            throw new MessageRecalledException();
        }

        boolean changed = messageRepository.setReaction(conversationId, messageId, userId, reaction);
        Message fresh = reload(conversationId, messageId);
        if (!changed && fresh.isRecalled()) {
            // The recall landed between the read and the update, and it wins.
            throw new MessageRecalledException();
        }
        MessageUpdateResponse update = updateOf(fresh);
        if (changed) {
            eventPublisher.messageUpdated(conversation, update);
        }
        return update;
    }

    @Override
    public MessageUpdateResponse unreact(String conversationId, Long userId, String messageId) {
        Conversation conversation = conversationService.requireParticipant(userId, conversationId);
        messageRepository.findInConversation(conversationId, messageId).orElseThrow(MessageNotFoundException::new);

        // Taking a reaction back is always allowed, blocked or not: it only removes something.
        boolean changed = messageRepository.clearReaction(conversationId, messageId, userId);
        MessageUpdateResponse update = updateOf(reload(conversationId, messageId));
        if (changed) {
            eventPublisher.messageUpdated(conversation, update);
        }
        return update;
    }

    @Override
    public MessageUpdateResponse recall(String conversationId, Long userId, String messageId) {
        Conversation conversation = conversationService.requireParticipant(userId, conversationId);
        // Deleted-from-my-side as a whole is a view of the past: what that covers is not mine to recall.
        Instant cleared = clearedAt(userId, conversationId);
        Message message = messageRepository.findInConversation(conversationId, messageId)
                .filter(found -> cleared == null || found.getSentAt().isAfter(cleared))
                .orElseThrow(MessageNotFoundException::new);
        // Checked here, in the service, and again by the update's own filter: only the sender's id
        // can match, whatever any client shows. That the author may have deleted it from their own
        // side makes no difference — it still is their message.
        if (!userId.equals(message.getSenderId())) {
            throw new NotMessageAuthorException();
        }

        boolean changed = messageRepository.recall(conversationId, messageId, userId, Instant.now());
        MessageUpdateResponse update = updateOf(reload(conversationId, messageId));
        if (changed) {
            // Not lastMessageAt, not requestMessage: only what the preview shows.
            conversationRepository.markLastMessageRecalled(conversationId, userId, message.getSentAt());
            eventPublisher.messageUpdated(conversation, update);
            conversationRepository.findByIdAndDeletedAtIsNull(conversationId)
                    .ifPresent(eventPublisher::conversationChanged);
        }
        return update;
    }

    @Override
    public void hide(String conversationId, Long userId, String messageId) {
        Conversation conversation = conversationService.requireParticipant(userId, conversationId);
        Instant cleared = clearedAt(userId, conversationId);
        messageRepository.findInConversation(conversationId, messageId)
                .filter(found -> cleared == null || found.getSentAt().isAfter(cleared))
                .orElseThrow(MessageNotFoundException::new);

        messageRepository.hide(conversationId, messageId, userId);
        conversationRepository.markPreviewHiddenBy(conversationId, userId);

        // To this user alone: the other participant's copy, and their preview, are untouched.
        eventPublisher.toUser(userId, ChatEvent.hidden(conversationId, messageId));
        conversationRepository.findByIdAndDeletedAtIsNull(conversation.getId())
                .ifPresent(fresh -> eventPublisher.conversationChangedFor(fresh, userId));
    }

    /** When the viewer deleted the whole conversation from their side, or null. */
    private Instant clearedAt(Long viewerId, String conversationId) {
        return prefsRepository.findById(viewerId).map(prefs -> prefs.clearedAt(conversationId)).orElse(null);
    }

    /** Deleted for this viewer one message at a time, or covered by their deleting the conversation. */
    private static boolean hiddenFrom(Message message, Long viewerId, Instant cleared) {
        return message.isHiddenFor(viewerId) || (cleared != null && !message.getSentAt().isAfter(cleared));
    }

    private Message reload(String conversationId, String messageId) {
        return messageRepository.findInConversation(conversationId, messageId)
                .orElseThrow(MessageNotFoundException::new);
    }

    /** A bare heart arrives from some keyboards without its emoji selector; it is the same reaction. */
    private static String canonical(String emoji) {
        return "\u2764".equals(emoji) ? "\u2764\uFE0F" : emoji;
    }

    /** How many messages one delivery acknowledgement may name, and how far the server looks for gaps. */
    static final int MAX_ACK_MESSAGES = 100;
    private static final int GAP_WALK_LIMIT = 500;

    /** The thread's own order: by time, and by id for messages sent in the same millisecond. */
    private static final Comparator<Message> THREAD_ORDER =
            Comparator.comparing(Message::getSentAt).thenComparing(Message::getId);

    @Override
    public ReceiptView markRead(String conversationId, Long currentUserId, String messageId) {
        Conversation conversation = conversationService.requireParticipant(currentUserId, conversationId);
        Instant cleared = clearedAt(currentUserId, conversationId);
        Message through;
        if (messageId == null) {
            through = messageRepository.findNewestOthers(conversationId, currentUserId).orElse(null);
        } else {
            through = messageRepository.findInConversation(conversationId, messageId).orElse(null);
            if (through == null || through.isDeleted() || currentUserId.equals(through.getSenderId())
                    || hiddenFrom(through, currentUserId, cleared)) {
                throw new InvalidReceiptException();
            }
        }
        if (through == null) {
            return marksOf(conversation, currentUserId);
        }
        Instant now = Instant.now();
        boolean read = conversationRepository.advanceMark(conversationId, currentUserId,
                ConversationRepositoryCustom.MarkKind.READ, through.getId(), through.getSentAt(), now);
        boolean delivered = conversationRepository.advanceMark(conversationId, currentUserId,
                ConversationRepositoryCustom.MarkKind.DELIVERED, through.getId(), through.getSentAt(), now);
        return settle(conversationId, currentUserId, read || delivered);
    }

    @Override
    public ReceiptView markDelivered(String conversationId, Long currentUserId, List<String> messageIds) {
        Conversation conversation = conversationService.requireParticipant(currentUserId, conversationId);
        List<String> ids = messageIds == null ? List.of()
                : messageIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty() || ids.size() > MAX_ACK_MESSAGES) {
            throw new InvalidReceiptException();
        }
        Map<String, Message> found = new HashMap<>();
        for (Message message : messageRepository.findInConversation(conversationId, ids)) {
            found.put(message.getId(), message);
        }
        List<Message> named = new ArrayList<>();
        for (String id : ids) {
            Message message = found.get(id);
            // Not here, or the caller's own: nothing this person can have received.
            if (message == null || message.isDeleted() || currentUserId.equals(message.getSenderId())) {
                throw new InvalidReceiptException();
            }
            named.add(message);
        }
        named.sort(THREAD_ORDER);
        Message last = named.get(named.size() - 1);
        Set<String> namedIds = new HashSet<>(ids);
        Instant cleared = clearedAt(currentUserId, conversationId);

        ReceiptMark current = conversation.getDeliveredMark() == null ? null
                : conversation.getDeliveredMark().get(String.valueOf(currentUserId));
        // The stretch the mark would cross. With no mark yet it starts at the first message named:
        // what came before it is history the person opened the thread onto, not a gap.
        List<Message> candidates = new ArrayList<>();
        if (current == null) {
            candidates.add(named.get(0));
            candidates.addAll(messageRepository.findOthersBetween(conversationId, currentUserId,
                    named.get(0).getSentAt(), named.get(0).getId(), last.getSentAt(), last.getId(), GAP_WALK_LIMIT));
        } else {
            candidates.addAll(messageRepository.findOthersBetween(conversationId, currentUserId,
                    current.sentAt(), current.messageId(), last.getSentAt(), last.getId(), GAP_WALK_LIMIT));
        }
        Message through = null;
        for (Message candidate : candidates) {
            if (hiddenFrom(candidate, currentUserId, cleared)) {
                continue;
            }
            if (!namedIds.contains(candidate.getId())) {
                break;
            }
            through = candidate;
        }
        if (through == null) {
            return marksOf(conversation, currentUserId);
        }
        boolean advanced = conversationRepository.advanceMark(conversationId, currentUserId,
                ConversationRepositoryCustom.MarkKind.DELIVERED, through.getId(), through.getSentAt(), Instant.now());
        return settle(conversationId, currentUserId, advanced);
    }

    @Override
    public ReceiptView receipts(String conversationId, Long currentUserId) {
        Conversation conversation = conversationService.requireParticipant(currentUserId, conversationId);
        Long peer = conversation.getParticipantIds().stream()
                .filter(id -> !id.equals(currentUserId)).findFirst().orElse(currentUserId);
        return marksOf(conversation, peer);
    }

    /** Reloads after a change and, when something moved, tells the other side where this user now is. */
    private ReceiptView settle(String conversationId, Long userId, boolean moved) {
        Conversation fresh = conversationRepository.findByIdAndDeletedAtIsNull(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
        ReceiptView view = marksOf(fresh, userId);
        if (moved) {
            eventPublisher.receipt(fresh, view);
        }
        return view;
    }

    private static ReceiptView marksOf(Conversation conversation, Long userId) {
        String key = String.valueOf(userId);
        return new ReceiptView(conversation.getId(), userId,
                conversation.getDeliveredMark() == null ? null : conversation.getDeliveredMark().get(key),
                conversation.getReadMark() == null ? null : conversation.getReadMark().get(key));
    }
}
