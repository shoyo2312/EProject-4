package com.tiktok.videoservice.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * The listener factory for user.privacy-events: it retries a failed record every 5 s until it
 * succeeds, whatever the failure. kafka-lib's handler does that only for an outage it recognises;
 * anything else gets three attempts and a DLT that nothing replays. The topic is low-volume and
 * ordered per user, so stalling beats dropping a switch — a lost switch to private leaves the
 * account's videos public.
 *
 * <p>ponytail: a poison event that parses stalls privacy events on its partition, visible as
 * consumer lag and an ERROR every 5 s; one that does not parse still goes to the DLT. The upgrade
 * path is a DLT replay tool.
 */
@Configuration
public class KafkaUnlimitedRetryConfig {

    public static final String UNLIMITED_RETRY_FACTORY = "unlimitedRetryKafkaListenerContainerFactory";

    @Bean(UNLIMITED_RETRY_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> unlimitedRetryKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory,
            KafkaOperations<Object, Object> kafkaOperations) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
                new DeadLetterPublishingRecoverer(kafkaOperations),
                new FixedBackOff(5_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        errorHandler.addNotRetryableExceptions(JsonProcessingException.class);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
