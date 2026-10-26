package com.tiktok.adminservice.config;

import com.tiktok.kafka.dlt.DeadLetters;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class DeadLetterConfig {

    /**
     * Built from the connection settings Boot gives every client here, {@code spring.kafka.properties}
     * included — which is how this reaches a broker behind SASL, where {@code make dlt-replay} cannot.
     */
    @Bean
    public DeadLetters deadLetters(KafkaProperties kafkaProperties,
                                   @Value("${admin.dead-letters.timeout:20s}") Duration timeout) {
        return new DeadLetters(kafkaProperties.buildAdminProperties(), timeout);
    }
}
