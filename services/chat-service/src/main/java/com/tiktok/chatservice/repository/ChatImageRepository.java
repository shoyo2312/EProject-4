package com.tiktok.chatservice.repository;

import com.tiktok.chatservice.entity.ChatImage;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatImageRepository extends MongoRepository<ChatImage, String>, ChatImageRepositoryCustom {
}
