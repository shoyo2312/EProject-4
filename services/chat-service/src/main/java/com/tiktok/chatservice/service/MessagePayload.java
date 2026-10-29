package com.tiktok.chatservice.service;

import com.tiktok.chatservice.dto.request.SendMessageRequest;
import com.tiktok.chatservice.entity.MessageType;
import com.tiktok.chatservice.exception.InvalidMessageException;
import com.tiktok.chatservice.exception.InvalidStickerException;
import com.tiktok.chatservice.sticker.StickerCatalog;
import com.tiktok.common.exception.BadRequestException;

/**
 * A send request checked against its own type, before anything about the conversation is touched:
 * a payload that is wrong must not promote a request, reopen a deleted one or spend a sender's one
 * message on its way to being refused.
 *
 * <p>The client names a sticker by id only. Whether that id exists, and is still offered, is the
 * catalog's answer — never a URL or storage key from the request.
 */
record MessagePayload(MessageType type, String content, String stickerId, String videoId) {

    static final int MAX_TEXT = 2000;

    static MessagePayload from(SendMessageRequest request, StickerCatalog catalog) {
        MessageType type = request.type() == null ? MessageType.TEXT : request.type();
        if (type != MessageType.VIDEO && request.videoId() != null) {
            throw new InvalidMessageException("Only VIDEO messages can carry a videoId");
        }
        if ((request.videoId() != null && request.videoId().length() > 64)
                || (request.clientRequestId() != null && request.clientRequestId().length() > 64)) {
            throw new InvalidMessageException("Message ids must be at most 64 characters");
        }
        return switch (type) {
            case VIDEO -> video(request);
            case TEXT -> text(request);
            case STICKER -> sticker(request, catalog);
        };
    }

    private static MessagePayload video(SendMessageRequest request) {
        if (request.videoId() == null || request.videoId().isBlank() || request.stickerId() != null) {
            throw new InvalidMessageException("VIDEO requires a videoId and cannot carry a stickerId");
        }
        String note = request.content() == null ? null : request.content().trim();
        if (note != null && note.isBlank()) {
            note = null;
        }
        if (note != null && note.length() > 300) {
            throw new InvalidMessageException("Video caption must be at most 300 characters after trimming");
        }
        return new MessagePayload(MessageType.VIDEO, note, null, request.videoId());
    }

    private static MessagePayload text(SendMessageRequest request) {
        if (request.stickerId() != null) {
            throw new InvalidMessageException("A text message cannot carry a stickerId");
        }
        String content = request.content();
        if (content == null || content.isBlank()) {
            throw new BadRequestException("VALIDATION_ERROR", "content: must not be blank");
        }
        if (content.length() > MAX_TEXT) {
            throw new BadRequestException("VALIDATION_ERROR", "content: size must be between 0 and " + MAX_TEXT);
        }
        return new MessagePayload(MessageType.TEXT, content, null, null);
    }

    private static MessagePayload sticker(SendMessageRequest request, StickerCatalog catalog) {
        if (request.content() != null) {
            throw new InvalidMessageException("A sticker cannot carry text");
        }
        if (!catalog.sendable(request.stickerId())) {
            throw new InvalidStickerException();
        }
        return new MessagePayload(MessageType.STICKER, null, request.stickerId(), null);
    }
}
