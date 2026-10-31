package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.ChatUserPrefs;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatUserPrefsRepository extends MongoRepository<ChatUserPrefs, Long>, ChatUserPrefsRepositoryCustom {
}
