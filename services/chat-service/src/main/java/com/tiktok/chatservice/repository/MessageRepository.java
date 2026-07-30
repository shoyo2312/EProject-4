package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface MessageRepository extends MongoRepository<Message, String> {

    List<Message> findByConversationIdAndDeletedAtIsNullAndSentAtBefore(
            String conversationId, Instant before, Pageable pageable);
}
