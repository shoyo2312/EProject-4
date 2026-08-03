package com.tiktok.kafka.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Without this, a poison message (bad JSON, or any exception thrown from a @KafkaListener) has
 * no configured recovery: Spring Kafka's default behavior retries it forever on the same
 * partition, blocking every message behind it. This retries transient failures a bounded
 * number of times, then routes the message to a "<topic>.DLT" dead-letter topic instead of
 * stalling the consumer. Services can override by declaring their own DefaultErrorHandler bean.
 */
@AutoConfiguration
@ConditionalOnClass(KafkaListener.class)
public class KafkaConsumerAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> kafkaOperations) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaOperations);
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3));
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class);
        return errorHandler;
    }
}
