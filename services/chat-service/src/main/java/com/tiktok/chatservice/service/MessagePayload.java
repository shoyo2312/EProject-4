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
record MessagePayload(MessageType type, String content, String stickerId, String videoId, String storyId,
                      String imageId) {

    static final int MAX_TEXT = 2000;
    static final int MAX_STORY_REPLY = 1000;
    static final int MAX_IMAGE_CAPTION = 1000;

    static MessagePayload from(SendMessageRequest request, StickerCatalog catalog) {
        MessageType type = request.type() == null ? MessageType.TEXT : request.type();
        if (type != MessageType.VIDEO && request.videoId() != null) {
            throw new InvalidMessageException("Only VIDEO messages can carry a videoId");
        }
        if (type != MessageType.IMAGE && request.imageId() != null) {
            throw new InvalidMessageException("Only IMAGE messages can carry an imageId");
        }
        if (type != MessageType.STORY_REPLY && request.storyId() != null) {
            throw new InvalidMessageException("Only STORY_REPLY messages can carry a storyId");
        }
        if ((request.videoId() != null && request.videoId().length() > 64)
                || (request.clientRequestId() != null && request.clientRequestId().length() > 64)) {
            throw new InvalidMessageException("Message ids must be at most 64 characters");
        }
        return switch (type) {
            case VIDEO -> video(request);
            case TEXT -> text(request);
            case STORY_REPLY -> storyReply(request);
            case STICKER -> sticker(request, catalog);
            case IMAGE -> image(request);
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
        return new MessagePayload(MessageType.VIDEO, note, null, request.videoId(), null, null);
    }

    private static MessagePayload image(SendMessageRequest request) {
        if (request.imageId() == null || request.imageId().isBlank() || request.stickerId() != null
                || request.videoId() != null || request.storyId() != null) {
            throw new InvalidMessageException("IMAGE requires an imageId and cannot carry anything but a caption");
        }
        String caption = request.content() == null ? null : request.content().trim();
        if (caption != null && caption.isEmpty()) {
            caption = null;
        }
        if (caption != null && caption.length() > MAX_IMAGE_CAPTION) {
            throw new BadRequestException("VALIDATION_ERROR", "content: size must be between 0 and " + MAX_IMAGE_CAPTION);
        }
        return new MessagePayload(MessageType.IMAGE, caption, null, null, null, request.imageId());
    }

    private static MessagePayload storyReply(SendMessageRequest request) {
        if (request.storyId() == null || request.storyId().isBlank() || request.stickerId() != null
                || request.videoId() != null || request.replyToMessageId() != null) {
            throw new InvalidMessageException("STORY_REPLY requires a storyId and nothing else but text");
        }
        String text = request.content() == null ? "" : request.content().trim();
        if (text.isEmpty()) {
            throw new BadRequestException("VALIDATION_ERROR", "content: must not be blank");
        }
        if (text.length() > MAX_STORY_REPLY) {
            throw new BadRequestException("VALIDATION_ERROR", "content: size must be between 1 and " + MAX_STORY_REPLY);
        }
        return new MessagePayload(MessageType.STORY_REPLY, text, null, null, request.storyId(), null);
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
        return new MessagePayload(MessageType.TEXT, content, null, null, null, null);
    }

    private static MessagePayload sticker(SendMessageRequest request, StickerCatalog catalog) {
        if (request.content() != null) {
            throw new InvalidMessageException("A sticker cannot carry text");
        }
        if (!catalog.sendable(request.stickerId())) {
            throw new InvalidStickerException();
        }
        return new MessagePayload(MessageType.STICKER, null, request.stickerId(), null, null, null);
    }
}
