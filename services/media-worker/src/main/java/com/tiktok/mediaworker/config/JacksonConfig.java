package com.tiktok.mediaworker.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * media-worker has no web/webflux starter (it's a pure Kafka consumer + MinIO client), so
 * Spring Boot's JacksonAutoConfiguration never applies — its ObjectMapper bean is gated on
 * Jackson2ObjectMapperBuilder, a class that only ships with spring-web. Registering the
 * bean here directly is simpler than pulling in spring-web just for that condition to pass.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
