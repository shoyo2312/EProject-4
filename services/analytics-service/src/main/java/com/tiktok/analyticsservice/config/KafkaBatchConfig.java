package com.tiktok.analyticsservice.config;

import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;

/**
 * A second listener factory that hands a listener the whole poll at once, for the streams that
 * grow with every scroll and tap: watch sessions, likes, shares and comments.
 *
 * <p>ClickHouse writes every INSERT as a new part on disk and merges them in the background. One
 * INSERT per Kafka record is one part per record, which under real traffic outruns the merges
 * until inserts start failing with "too many parts". A batch listener writes each poll as one
 * INSERT, so the number of parts follows the number of polls rather than the number of events.
 *
 * <p>Configured by the same configurer as Boot's default factory, so it carries kafka-lib's error
 * handler and dead-letter topic too. The default factory stays as it is for the topics that see a
 * handful of records a day.
 */
@Configuration
public class KafkaBatchConfig {

    public static final String BATCH_FACTORY = "batchKafkaListenerContainerFactory";

    @Bean(BATCH_FACTORY)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> batchKafkaListenerContainerFactory(
            ConcurrentKafkaListenerContainerFactoryConfigurer configurer,
            ConsumerFactory<Object, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        configurer.configure(factory, consumerFactory);
        factory.setBatchListener(true);
        return factory;
    }
}
