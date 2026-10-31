package com.tiktok.chatservice.service;

import com.tiktok.chatservice.client.BlockClient;
import com.tiktok.chatservice.dto.response.ImageView;
import com.tiktok.chatservice.entity.ChatImage;
import com.tiktok.chatservice.entity.Conversation;
import com.tiktok.chatservice.entity.Message;
import com.tiktok.chatservice.exception.InvalidImageException;
import com.tiktok.chatservice.exception.MessageNotFoundException;
import com.tiktok.chatservice.exception.MessageRequestLimitReachedException;
import com.tiktok.chatservice.exception.MessageRequestNotAcceptedException;
import com.tiktok.chatservice.repository.ChatImageRepository;
import com.tiktok.chatservice.repository.ChatImageRepositoryImpl;
import com.tiktok.chatservice.repository.ChatUserPrefsRepository;
import com.tiktok.chatservice.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Uploading and reading chat pictures. Uploading follows the sending rules (a block, a request nobody
 * accepted yet) so it cannot be a way round them; reading follows the message: the viewer must be in
 * the conversation, and a picture that was recalled or deleted from their side is not theirs to fetch.
 * A picture nobody has attached to a message yet is visible to its uploader alone.
 */
@Service
@RequiredArgsConstructor
public class ChatImageService {

    private final ConversationService conversationService;
    private final ChatImageRepository images;
    private final MessageRepository messages;
    private final ChatUserPrefsRepository prefsRepository;
    private final BlockClient blockClient;

    public ImageView upload(String conversationId, Long userId, byte[] bytes) {
        Conversation conversation = conversationService.requireParticipant(userId, conversationId);
        for (Long participant : conversation.getParticipantIds()) {
            if (!participant.equals(userId)) {
                blockClient.requireNotBlocked(userId, participant);
            }
        }
        conversation = conversationService.promoteIfMutual(conversation);
        switch (SendPolicy.of(conversation, userId, Instant.now())) {
            case REQUEST_SENT -> throw new MessageRequestLimitReachedException();
            case AWAITING_ACCEPT -> throw new MessageRequestNotAcceptedException();
            default -> {
            }
        }
        ImageInspector.Inspected seen = ImageInspector.inspect(bytes);
        Instant now = Instant.now();
        ChatImage saved = images.save(ChatImage.builder()
                .id(Message.newId())
                .conversationId(conversationId)
                .uploaderId(userId)
                .mime(seen.mime())
                .width(seen.width())
                .height(seen.height())
                .size(bytes.length)
                .data(bytes)
                .createdAt(now)
                .expiresAt(now.plus(ChatImageRepositoryImpl.UNCLAIMED_TTL))
                .build());
        return view(saved);
    }

    /** The bytes of a picture this viewer may see, or {@link MessageNotFoundException}. */
    public ChatImage open(String conversationId, Long viewerId, String imageId) {
        conversationService.requireParticipant(viewerId, conversationId);
        ChatImage image = images.findInConversation(conversationId, imageId).orElseThrow(MessageNotFoundException::new);
        if (image.getMessageId() == null) {
            if (!viewerId.equals(image.getUploaderId())) {
                throw new MessageNotFoundException();
            }
            return image;
        }
        Instant cleared = prefsRepository.findById(viewerId).map(p -> p.clearedAt(conversationId)).orElse(null);
        Message message = messages.findInConversation(conversationId, image.getMessageId())
                .filter(m -> !m.isRecalled() && !m.isDeleted() && !m.isHiddenFor(viewerId)
                        && (cleared == null || m.getSentAt().isAfter(cleared)))
                .orElseThrow(MessageNotFoundException::new);
        if (!imageId.equals(message.getImageId())) {
            throw new MessageNotFoundException();
        }
        return image;
    }

    /** Metadata of the pictures named, by id. */
    public Map<String, ImageView> views(String conversationId, Collection<String> imageIds) {
        if (imageIds.isEmpty()) {
            return Map.of();
        }
        return images.findMeta(conversationId, imageIds).stream()
                .collect(Collectors.toMap(ChatImage::getId, ChatImageService::view));
    }

    public static ImageView view(ChatImage image) {
        return new ImageView(image.getId(),
                "/api/v1/conversations/" + image.getConversationId() + "/images/" + image.getId(),
                image.getMime(), image.getWidth(), image.getHeight(), image.getSize());
    }

    public static byte[] read(MultipartFile file) {
        try {
            if (file.getSize() > ImageInspector.MAX_BYTES) {
                throw new InvalidImageException("IMAGE_TOO_LARGE", "Ảnh tối đa 5 MB");
            }
            return file.getBytes();
        } catch (IOException e) {
            throw new InvalidImageException("IMAGE_CORRUPT", "Không đọc được ảnh này");
        }
    }
}
