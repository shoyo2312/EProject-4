package com.tiktok.chatservice.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.tiktok.chatservice.entity.MessageType;

import java.time.Instant;
import java.util.List;

/**
 * {@code content} is null on a sticker; {@code sticker} is null on text. {@code sticker} is resolved
 * from the catalog when the response is built, so a sticker that is no longer offered for sending
 * still arrives with everything needed to draw it.
 *
 * <p>{@code video} is resolved from video-service for this viewer on every render, never stored.
 * {@code story} is resolved from story-service for this viewer on every render, never stored.
 * {@code replayed} is transport-only (not JSON): REST uses it to return 200 for a deduplicated send.
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
        long revision,
        @JsonInclude(JsonInclude.Include.NON_NULL) VideoCard video,
        @JsonInclude(JsonInclude.Include.NON_NULL) StoryCard story,
        @JsonIgnore boolean replayed
) {

    public MessageResponse(String id, String conversationId, Long senderId, String content, Instant sentAt,
                           MessageType type, String stickerId, StickerView sticker, String replyToMessageId,
                           ReplyQuote replyTo, Instant recalledAt, List<ReactionView> reactions, long revision) {
        this(id, conversationId, senderId, content, sentAt, type, stickerId, sticker, replyToMessageId,
                replyTo, recalledAt, reactions, revision, null, null, false);
    }

    public MessageResponse withVideo(VideoCard card) {
        return new MessageResponse(id, conversationId, senderId, content, sentAt, type, stickerId, sticker,
                replyToMessageId, replyTo, recalledAt, reactions, revision, card, story, replayed);
    }

    public MessageResponse withStory(StoryCard card) {
        return new MessageResponse(id, conversationId, senderId, content, sentAt, type, stickerId, sticker,
                replyToMessageId, replyTo, recalledAt, reactions, revision, video, card, replayed);
    }

    public MessageResponse asReplay() {
        return new MessageResponse(id, conversationId, senderId, content, sentAt, type, stickerId, sticker,
                replyToMessageId, replyTo, recalledAt, reactions, revision, video, story, true);
    }

    /** A message with no reply, recall or reactions — the shape every message had before they existed. */
    public MessageResponse(String id, String conversationId, Long senderId, String content, Instant sentAt,
                           MessageType type, String stickerId, StickerView sticker) {
        this(id, conversationId, senderId, content, sentAt, type, stickerId, sticker, null, null, null, List.of(), 0L);
    }

    public MessageResponse withSticker(StickerView resolved) {
        return new MessageResponse(id, conversationId, senderId, content, sentAt, type, stickerId, resolved,
                replyToMessageId, replyTo, recalledAt, reactions, revision, video, story, replayed);
    }

    public MessageResponse withReplyTo(ReplyQuote quote) {
        return new MessageResponse(id, conversationId, senderId, content, sentAt, type, stickerId, sticker,
                replyToMessageId, quote, recalledAt, reactions, revision, video, story, replayed);
    }

    public MessageResponse withReactions(List<ReactionView> views) {
        return new MessageResponse(id, conversationId, senderId, content, sentAt, type, stickerId, sticker,
                replyToMessageId, replyTo, recalledAt, views, revision, video, story, replayed);
    }

    /** The tombstone: what is left of a message once its sender has taken it back. */
    public MessageResponse redacted() {
        return new MessageResponse(id, conversationId, senderId, null, sentAt, type, null, null,
                replyToMessageId, replyTo, recalledAt, List.of(), revision);
    }
}
