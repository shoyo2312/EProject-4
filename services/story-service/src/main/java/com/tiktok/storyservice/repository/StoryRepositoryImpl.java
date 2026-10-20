package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryModeration;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;

import static org.springframework.data.mongodb.core.query.Criteria.where;

/** Picked up by Spring Data through the {@code <Repository>Impl} naming convention. */
@RequiredArgsConstructor
public class StoryRepositoryImpl implements StoryRepositoryCustom {

    private final MongoTemplate mongoTemplate;

    @Override
    public void incrementViewCount(String storyId) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(storyId)),
                new Update().inc("viewCount", 1), Story.class);
    }

    @Override
    public boolean decide(String storyId, StoryStatus to, Instant expiresAt, Integer durationSeconds,
                          StoryModeration moderation) {
        Update update = new Update()
                .set("status", to)
                .set("durationSeconds", durationSeconds)
                .set("moderation", moderation);
        if (expiresAt != null) {
            update.set("expiresAt", expiresAt);
        }
        return mongoTemplate.updateFirst(
                Query.query(where("_id").is(storyId).and("status").is(StoryStatus.PENDING_MODERATION)),
                update, Story.class).getModifiedCount() == 1;
    }

    @Override
    public void markEventPublished(String storyId, Instant at) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(storyId)),
                new Update().set("eventPublishedAt", at), Story.class);
    }

    @Override
    public void updateVisibility(String storyId, StoryVisibility visibility) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(storyId)),
                new Update().set("visibility", visibility), Story.class);
    }

    @Override
    public void markDeleted(String storyId, Instant at) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(storyId)),
                new Update().set("deletedAt", at), Story.class);
    }
}
