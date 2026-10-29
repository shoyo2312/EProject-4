package com.tiktok.chatservice.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.validation.MessageContentSize;
import jakarta.validation.constraints.Size;

/**
 * One request body for REST and STOMP alike. Which fields must be present depends on {@code type}, so
 * that is checked in {@code MessagePayload} rather than with annotations that would have to be
 * unconditional: a {@code @NotBlank} content would refuse every sticker.
 *
 * <ul>
 *   <li>{@code TEXT} (or no type, which is what every older client sends): {@code content}, not blank,
 *       at most 2000 characters; no {@code stickerId}.</li>
 *   <li>{@code STICKER}: {@code stickerId} from the catalog; no {@code content}.</li>
 *   <li>{@code VIDEO}: {@code videoId}, with an optional trimmed caption of at most 300 UTF-16 units.</li>
 * </ul>
 *
 * {@code replyToMessageId} makes either kind a reply. Only the id travels: the quote is read back
 * from the stored original, never from anything the client says about it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@MessageContentSize
public record SendMessageRequest(
        MessageType type,
        String content,
        String stickerId,
        @Size(max = 64) String replyToMessageId,
        @Size(max = 64) String videoId,
        @Size(max = 64) String clientRequestId
) {

    public SendMessageRequest(MessageType type, String content, String stickerId, String replyToMessageId) {
        this(type, content, stickerId, replyToMessageId, null, null);
    }

    public static SendMessageRequest video(String videoId, String note, String clientRequestId) {
        return new SendMessageRequest(MessageType.VIDEO, note, null, null, videoId, clientRequestId);
    }

    public SendMessageRequest(MessageType type, String content, String stickerId) {
        this(type, content, stickerId, null);
    }

    /** The shape every client had before stickers: just text. */
    public SendMessageRequest(String content) {
        this(null, content, null, null);
    }

    public static SendMessageRequest sticker(String stickerId) {
        return new SendMessageRequest(MessageType.STICKER, null, stickerId, null);
    }

    public SendMessageRequest replyingTo(String messageId) {
        return new SendMessageRequest(type, content, stickerId, messageId, videoId, clientRequestId);
    }
}
