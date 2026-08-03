package com.tiktok.kafka.config;

import com.tiktok.kafka.outbox.OutboxDispatcher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaOperations;

/**
 * Exposes {@link OutboxDispatcher} to any service that has a Kafka template configured. Only
 * services with an outbox actually inject it, so this stays inert everywhere else.
 */
@AutoConfiguration(after = KafkaAutoConfiguration.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class KafkaOutboxAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaOperations.class)
    public OutboxDispatcher outboxDispatcher(KafkaOperations<String, String> kafkaOperations,
                                             OutboxProperties properties) {
        return new OutboxDispatcher(kafkaOperations, properties.getAckTimeout());
    }
}
