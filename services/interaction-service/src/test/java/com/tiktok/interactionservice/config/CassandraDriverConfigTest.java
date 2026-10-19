package com.tiktok.interactionservice.config;

import com.datastax.oss.driver.api.core.config.DefaultDriverOption;
import com.datastax.oss.driver.api.core.config.DriverConfigLoader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A host restart brings this service up beside Cassandra rather than after it, and depends_on
 * does not apply to containers the Docker daemon restarts. Without reconnect-on-init the driver
 * throws on the first refused connection and the context dies; with it, building the session
 * waits for a node instead.
 */
class CassandraDriverConfigTest {

    private static final String DRIVER_CONFIG = "cassandra-driver.conf";

    @Test
    void driverConfigReconnectsOnInit() {
        boolean reconnect = DriverConfigLoader.fromClasspath(DRIVER_CONFIG).getInitialConfig()
                .getDefaultProfile().getBoolean(DefaultDriverOption.RECONNECT_ON_INIT);

        assertThat(reconnect).isTrue();
    }

    @Test
    void applicationYmlPointsSpringBootAtThatDriverConfig() throws Exception {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));

        assertThat(sources.get(0).getProperty("spring.cassandra.config")).isEqualTo("classpath:" + DRIVER_CONFIG);
    }
}
