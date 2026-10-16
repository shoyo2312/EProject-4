package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Message;

import java.time.Instant;
import java.util.List;

public interface MessageRepositoryCustom {

    /**
     * One page of a conversation's live messages, newest first, keyset-paged on
     * {@code (sentAt, _id)} — the order {@code conversation_sent_id_idx} keeps them in.
     *
     * @param beforeSentAt where the previous page stopped; null for the newest page
     * @param beforeId     the {@code _id} that breaks ties at {@code beforeSentAt}. Null only for a
     *                     cursor issued before ids were part of it, which then pages strictly older
     */
    List<Message> findPage(String conversationId, Instant beforeSentAt, String beforeId, int limit);
}
