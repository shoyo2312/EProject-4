package com.tiktok.chatservice.dto.response;

import com.tiktok.chatservice.entity.MessageType;

import java.time.Instant;
import java.util.List;

/**
 * {@code content} is null on a sticker; {@code sticker} is null on text. {@code sticker} is resolved
 * from the catalog when the response is built, so a sticker that is no longer offered for sending
 * still arrives with everything needed to draw it.
 *
 * <p>A recalled message ({@code recalledAt} set) has no content, sticker or reactions: the text is
 * removed from storage, not just hidden here. {@code replyTo} is resolved for the viewer, so two
 * participants can be given different answers; {@code reactions} and the rest are the same for both.
 */
public record MessageResponse(
        String id,
        String conversationId,
        Long senderId,
        String content,
        Instant sentAt,
        MessageType type,
        String stickerId,
        StickerView sticker,
        String replyToMessageId,
        ReplyQuote replyTo,
        Instant recalledAt,
        List<ReactionView> reactions,
        long revision
) {

    /** A message with no reply, recall or reactions — the shape every message had before they existed. */
    public MessageResponse(String id, String conversationId, Long senderId, String content, Instant sentAt,
                           MessageType type, String stickerId, StickerView sticker) {
        this(id, conversationId, senderId, content, sentAt, type, stickerId, sticker, null, null, null, List.of(), 0L);
    }

    public MessageResponse withSticker(StickerView resolved) {
        return new MessageResponse(id, conversationId, senderId, content, sentAt, type, stickerId, resolved,
                replyToMessageId, replyTo, recalledAt, reactions, revision);
    }

    public MessageResponse withReplyTo(ReplyQuote quote) {
        return new MessageResponse(id, conversationId, senderId, content, sentAt, type, stickerId, sticker,
                replyToMessageId, quote, recalledAt, reactions, revision);
    }

    public MessageResponse withReactions(List<ReactionView> views) {
        return new MessageResponse(id, conversationId, senderId, content, sentAt, type, stickerId, sticker,
                replyToMessageId, replyTo, recalledAt, views, revision);
    }

    /** The tombstone: what is left of a message once its sender has taken it back. */
    public MessageResponse redacted() {
        return new MessageResponse(id, conversationId, senderId, null, sentAt, type, null, null,
                replyToMessageId, replyTo, recalledAt, List.of(), revision);
    }
}
