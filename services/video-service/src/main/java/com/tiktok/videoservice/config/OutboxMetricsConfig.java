package com.tiktok.videoservice.config;

import com.tiktok.kafka.outbox.OutboxBacklog;
import com.tiktok.videoservice.entity.Video;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * The outbox here is five slots on the video document, one per poll in VideoEventPublisher, so it
 * is five backlogs tagged by slot. Each filter is its poll's and reads the same index.
 */
@Configuration
public class OutboxMetricsConfig {

    /**
     * Unlike its poll, this counts a row parked by eventFailedAt: nothing sends that one until
     * someone clears the flag, which makes it exactly the stuck event these gauges are for.
     */
    @Bean
    public OutboxBacklog publishedOutboxBacklog(MongoTemplate mongoTemplate) {
        return backlog(mongoTemplate, "published",
                () -> where("eventPublishedAt").is(null).and("deletedAt").is(null),
                "createdAt", Video::getCreatedAt);
    }

    @Bean
    public OutboxBacklog deletedOutboxBacklog(MongoTemplate mongoTemplate) {
        return backlog(mongoTemplate, "deleted",
                () -> where("deletedAt").ne(null).and("deleteEventPublishedAt").is(null),
                "deletedAt", Video::getDeletedAt);
    }

    @Bean
    public OutboxBacklog visibilityOutboxBacklog(MongoTemplate mongoTemplate) {
        return backlog(mongoTemplate, "visibility",
                () -> where("visibilityEventPendingAt").ne(null).and("eventPublishedAt").ne(null),
                "visibilityEventPendingAt", Video::getVisibilityEventPendingAt);
    }

    /** Due when the trash window runs out. Counted from the deletion, every purge would read a month late. */
    @Bean
    public OutboxBacklog purgeOutboxBacklog(MongoTemplate mongoTemplate,
                                            @Value("${video.trash.retention:P30D}") Duration trashRetention) {
        return backlog(mongoTemplate, "purge",
                () -> where("deletedAt").ne(null).lt(Instant.now().minus(trashRetention))
                        .and("purgeEventPublishedAt").is(null),
                "deletedAt", video -> video.getDeletedAt().plus(trashRetention));
    }

    @Bean
    public OutboxBacklog purgeRequestOutboxBacklog(MongoTemplate mongoTemplate) {
        return backlog(mongoTemplate, "purge-request",
                () -> where("purgeRequestedAt").ne(null).and("purgeEventPublishedAt").is(null),
                "purgeRequestedAt", Video::getPurgeRequestedAt);
    }

    /** The criteria is rebuilt on every scrape because the purge cutoff moves with the clock. */
    private static OutboxBacklog backlog(MongoTemplate mongoTemplate, String slot, Supplier<Criteria> pending,
                                         String dueField, Function<Video, Instant> dueAt) {
        return new OutboxBacklog(slot,
                () -> mongoTemplate.count(query(pending.get()), Video.class),
                () -> {
                    Video oldest = mongoTemplate.findOne(query(pending.get()).with(Sort.by(dueField)), Video.class);
                    return oldest == null ? null : dueAt.apply(oldest);
                });
    }
}
