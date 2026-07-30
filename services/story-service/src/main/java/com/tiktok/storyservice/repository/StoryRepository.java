package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.Story;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface StoryRepository extends MongoRepository<Story, String> {

    Optional<Story> findByIdAndDeletedAtIsNull(String id);

    List<Story> findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(Long userId, Instant now);

    List<Story> findByUserIdInAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(List<Long> userIds, Instant now);
}
