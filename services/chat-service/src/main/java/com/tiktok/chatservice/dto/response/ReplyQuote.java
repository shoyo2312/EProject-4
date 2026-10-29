package com.tiktok.chatservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.tiktok.chatservice.entity.MessageType;

/**
 * The message being replied to, as the viewer is allowed to see it right now — resolved on every
 * read and never stored, so a recall is reflected everywhere at once.
 *
 * <p>{@code RECALLED} and {@code UNAVAILABLE} carry no text, sticker or type. {@code senderId} is
 * null when the original is unavailable to the viewer.
 */
public record ReplyQuote(
        String messageId,
        State state,
        Long senderId,
        MessageType type,
        String content,
        StickerView sticker,
        @JsonInclude(JsonInclude.Include.NON_NULL) VideoCard video
) {

    public ReplyQuote(String messageId, State state, Long senderId, MessageType type, String content, StickerView sticker) {
        this(messageId, state, senderId, type, content, sticker, null);
    }

    public enum State {
        OK,
        /** Taken back by its sender, for everyone. */
        RECALLED,
        /** Gone from this viewer's side (deleted for them) or never findable. */
        UNAVAILABLE
    }

    public static ReplyQuote recalled(String messageId, Long senderId) {
        return new ReplyQuote(messageId, State.RECALLED, senderId, null, null, null);
    }

    public static ReplyQuote unavailable(String messageId) {
        return new ReplyQuote(messageId, State.UNAVAILABLE, null, null, null, null);
    }
}
