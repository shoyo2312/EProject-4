package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StoryRepository extends MongoRepository<Story, String>, StoryRepositoryCustom {

    Optional<Story> findByIdAndDeletedAtIsNull(String id);

    List<Story> findTop100ByEventPublishedAtIsNullAndDeletedAtIsNullOrderByCreatedAtAsc();

    /** The owner's own live stories, every status, oldest first — the order they play in. */
    List<Story> findByUserIdAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(Long userId, Instant now);

    List<Story> findByUserIdInAndStatusAndDeletedAtIsNullAndExpiresAtAfterOrderByCreatedAtAsc(
            Collection<Long> userIds, StoryStatus status, Instant now);
}
