package com.tiktok.storyservice.config;

import com.tiktok.kafka.outbox.OutboxBacklog;
import com.tiktok.storyservice.entity.Story;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;

import java.time.Instant;

import static com.tiktok.storyservice.repository.StoryRepositoryImpl.announceable;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * The outbox gauges, read from the stories collection on every scrape: the dispatcher only sees the
 * batches it is handed, so a poller that died looks exactly like an idle outbox from inside it.
 * Each has the same filter as its poll in {@code StoryEventPublisher}.
 */
@Configuration
public class OutboxMetricsConfig {

    @Bean
    public OutboxBacklog storyCreatedOutboxBacklog(MongoTemplate mongoTemplate) {
        return new OutboxBacklog("story-created",
                () -> mongoTemplate.count(query(pending()), Story.class),
                () -> {
                    Story oldest = mongoTemplate.findOne(query(pending()).with(Sort.by("createdAt")), Story.class);
                    return oldest == null ? null : oldest.getCreatedAt();
                });
    }

    @Bean
    public OutboxBacklog storyActivatedOutboxBacklog(MongoTemplate mongoTemplate) {
        return new OutboxBacklog("story-activated",
                () -> mongoTemplate.count(query(announceable(Instant.now())), Story.class),
                () -> {
                    Story oldest = mongoTemplate.findOne(
                            query(announceable(Instant.now())).with(Sort.by("activatedAt")), Story.class);
                    return oldest == null ? null : oldest.getActivatedAt();
                });
    }

    private static Criteria pending() {
        return where("eventPublishedAt").is(null).and("deletedAt").is(null);
    }
}
