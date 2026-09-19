package com.tiktok.videoservice.config;

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
        environment.getPropertySources().addFirst(new MapPropertySource("env", Map.of("MONGO_HOST", "mongo.internal", "MONGO_USERNAME", "video", "MONGO_PASSWORD", "s3cret")));
        new YamlPropertySourceLoader().load("app", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("spring.data.mongodb.uri")).contains("video:s3cret@mongo.internal");
    }
}
