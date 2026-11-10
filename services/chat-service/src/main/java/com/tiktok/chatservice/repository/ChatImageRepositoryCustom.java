package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.ChatImage;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatImageRepositoryCustom {

    /**
     * Gives the upload to {@code messageId} if it is {@code uploaderId}'s, in this conversation and not
     * yet claimed — one conditional update, so two sends cannot both take it. True when it was taken.
     */
    boolean claim(String conversationId, String imageId, Long uploaderId, String messageId);

    /** Puts a claimed upload back up for expiry, after the message that claimed it was not stored. */
    void release(String messageId);

    void deleteByMessageId(String messageId);

    /** Everything but the bytes. */
    List<ChatImage> findMeta(String conversationId, Collection<String> imageIds);

    Optional<ChatImage> findInConversation(String conversationId, String imageId);
}
