package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryModeration;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryVisibility;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Instant;
import java.util.List;

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
        if (to == StoryStatus.ACTIVE) {
            update.set("activatedAt", Instant.now());
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

    /**
     * The stories StoryActivatedEvent is still owed for. {@code activatedAt} keeps out a story that
     * went live before the field existed; PRIVATE stays out until its owner widens it, and one
     * narrowed and widened again is not announced twice because {@code announcedAt} stays set.
     * Shared with the backlog gauge, so the poll and what the gauge counts cannot drift apart.
     */
    public static Criteria announceable(Instant now) {
        return where("status").is(StoryStatus.ACTIVE)
                .and("activatedAt").ne(null)
                .and("announcedAt").is(null)
                .and("deletedAt").is(null)
                .and("expiresAt").gt(now)
                .and("visibility").in(StoryVisibility.PUBLIC, StoryVisibility.FRIENDS);
    }

    @Override
    public List<Story> findAnnounceable(Instant now, int limit) {
        // ponytail: a collection scan per poll, fine while the collection holds at most 48h of
        // stories; a partial index on announcedAt is the upgrade.
        return mongoTemplate.find(Query.query(announceable(now)).with(Sort.by("activatedAt")).limit(limit),
                Story.class);
    }

    @Override
    public void markAnnounced(String storyId, Instant at) {
        mongoTemplate.updateFirst(Query.query(where("_id").is(storyId)),
                new Update().set("announcedAt", at), Story.class);
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
