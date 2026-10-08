package com.tiktok.authservice.config;

import com.tiktok.authservice.repository.OutboxEventRepository;
import com.tiktok.kafka.outbox.OutboxBacklog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** What OutboxPublisher has not got acknowledged yet, as kafka-lib's backlog gauges. */
@Configuration
public class OutboxMetricsConfig {

    @Bean
    public OutboxBacklog outboxBacklog(OutboxEventRepository outboxEventRepository) {
        return new OutboxBacklog("outbox_events",
                outboxEventRepository::countByPublishedAtIsNull,
                outboxEventRepository::oldestPendingCreatedAt);
    }
}
