package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.Message;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface MessageRepository extends MongoRepository<Message, String>, MessageRepositoryCustom {
}
