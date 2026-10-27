package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.dto.response.ChatEvent;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.dto.response.MessagePageResponse;
import com.tiktok.chatservice.dto.response.MessageResponse;
import com.tiktok.chatservice.dto.response.MessageUpdateResponse;
import com.tiktok.chatservice.dto.response.ReactionView;
import com.tiktok.chatservice.dto.response.ReplyQuote;
import com.tiktok.chatservice.dto.response.SendPermission;
import com.tiktok.chatservice.dto.response.StickerView;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.sticker.StickerCatalog;
import com.tiktok.chatservice.exception.InvalidReactionException;
import com.tiktok.chatservice.exception.InvalidReplyTargetException;
import com.tiktok.chatservice.exception.MessageNotFoundException;
import com.tiktok.chatservice.exception.MessageRecalledException;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.exception.MessageRequestNotAcceptedException;
import com.tiktok.chatservice.exception.NotMessageAuthorException;
import com.tiktok.chatservice.exception.ReactionNotAllowedException;
import com.tiktok.chatservice.mapper.MessageMapper;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.ConversationRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    @Override
    public MessageResponse sendMessage(String conversationId, Long senderId, SendMessageRequest request) {
        Conversation conversation = conversationService.requireParticipant(senderId, conversationId);
        // Per message, not only when the conversation opens: a block placed later has to stop a
        // thread that already exists.
        requireNoBlock(conversation, senderId);

        // Before anything below can change state: a payload that is wrong must leave the conversation,
        // its request and the sender's one message exactly as they were.
        MessagePayload payload = MessagePayload.from(request, stickerCatalog);
        String replyTo = requireReplyTarget(conversationId, senderId, request.replyToMessageId());

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
                .sentAt(now)
                .requestMessage(requestMessage ? Boolean.TRUE : null)
                .replyToMessageId(replyTo)
                .build();
        try {
            messageRepository.save(message);
        } catch (DuplicateKeyException e) {
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
        if (replyTo == null) {
            messagingTemplate.convertAndSend(TOPIC_PREFIX + conversationId, response);
            for (Long participant : conversation.getParticipantIds()) {
                if (!participant.equals(senderId)) {
                    eventPublisher.toUser(participant, ChatEvent.message(response));
                }
            }
        } else {
            // A reply's quote depends on who is looking — the other side may have deleted the
            // original from their own — so it is rendered per person and goes to each of them
            // privately, the sender's other tabs included, instead of one frame on the shared topic.
            for (Long participant : conversation.getParticipantIds()) {
                eventPublisher.toUser(participant, ChatEvent.message(render(message, participant)));
            }
        }
        if (requestMessage) {
            // The recipient learns of the request without knowing its id, and the sender's other
            // tabs lock their composer — both from the stored state, not from anything the client said.
            conversationRepository.findByIdAndDeletedAtIsNull(conversationId)
                    .ifPresent(eventPublisher::conversationChanged);
        }

        return response;
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

        return new MessagePageResponse(page.stream().map(m -> render(m, viewerId, cleared, originals)).toList(), nextCursor, hasMore);
    }

    /** A stored message as the viewer draws it: sticker resolved, reactions listed, a recall redacted, the quote worked out. */
    private MessageResponse render(Message message, Long viewerId) {
        Map<String, Message> originals = new HashMap<>();
        String replyTo = message.getReplyToMessageId();
        if (replyTo != null) {
            messageRepository.findInConversation(message.getConversationId(), replyTo)
                    .ifPresent(original -> originals.put(original.getId(), original));
        }
        return render(message, viewerId, clearedAt(viewerId, message.getConversationId()), originals);
    }

    private MessageResponse render(Message message, Long viewerId, Instant cleared, Map<String, Message> originals) {
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
            response = response.withReactions(reactionViews(message));
        }
        if (message.getReplyToMessageId() != null) {
            response = response.withReplyTo(quote(message.getReplyToMessageId(), originals.get(message.getReplyToMessageId()), viewerId, cleared));
        }
        return response;
    }

    /** What the viewer may see of the original right now: recalled and deleted-for-them carry no content. */
    private ReplyQuote quote(String id, Message original, Long viewerId, Instant cleared) {
        if (original == null || hiddenFrom(original, viewerId, cleared)) {
            return ReplyQuote.unavailable(id);
        }
        if (original.isRecalled()) {
            return ReplyQuote.recalled(id, original.getSenderId());
        }
        StickerView sticker = original.getType() == MessageType.STICKER
                ? stickerCatalog.find(original.getStickerId()).orElse(null) : null;
        return new ReplyQuote(id, ReplyQuote.State.OK, original.getSenderId(), original.getType(),
                shorten(original.getContent()), sticker);
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

    @Override
    public void markRead(String conversationId, Long currentUserId) {
        conversationService.requireParticipant(currentUserId, conversationId);
        conversationRepository.markRead(conversationId, currentUserId, Instant.now());
    }
}
