package com.tiktok.storyservice.config;

import com.tiktok.kafka.outbox.OutboxBacklog;
import com.tiktok.storyservice.entity.Story;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;

import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

/**
 * The outbox gauges, read from the stories collection on every scrape: the dispatcher only sees the
 * batches it is handed, so a poller that died looks exactly like an idle outbox from inside it.
 * Same filter as {@code StoryEventPublisher}'s poll.
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

    private static Criteria pending() {
        return where("eventPublishedAt").is(null).and("deletedAt").is(null);
    }
}
