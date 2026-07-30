package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Conversation;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ConversationRepository extends MongoRepository<Conversation, String> {

    Optional<Conversation> findByIdAndDeletedAtIsNull(String id);

    Optional<Conversation> findByParticipantKeyAndDeletedAtIsNull(String participantKey);

    List<Conversation> findByParticipantIdsAndDeletedAtIsNullOrderByLastMessageAtDesc(Long userId);
}
