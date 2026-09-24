package com.tiktok.authservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connection string was a literal pointing at localhost, so every deployment outside a
 * developer's machine had to override the whole property rather than set the host and
 * credentials the way every other service here does.
 */
class ConnectionSettingsTest {

    @Test
    void theDatabaseLocationComesFromTheEnvironment() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("env", Map.of("DB_HOST", "pg.internal", "DB_PORT", "6543")));
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("spring.datasource.url")).contains("pg.internal:6543");
    }

    /**
     * Social sign-in sends the avatar event on the request thread, and a send waits this long for
     * broker metadata. Left at Kafka's 60s default, a broker outage hung every provider login.
     */
    @Test
    void aProducerSendCannotHoldASignInForLong() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("spring.kafka.producer.properties[max.block.ms]", Long.class))
                .isNotNull()
                .isLessThanOrEqualTo(5_000L);
    }
}
