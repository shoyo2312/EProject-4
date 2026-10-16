package com.tiktok.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ManagementDefaultsTest {

    @Test
    void everyServiceExposesHealthInfoAndTheScrape_taggedWithItsName() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application",
                Map.of("spring.application.name", "video-service")));

        new ManagementDefaults().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health,info,prometheus");
        assertThat(environment.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
        assertThat(environment.getProperty("management.metrics.tags.application")).isEqualTo("video-service");
    }

    /** Defaults only: a service that says otherwise in its own configuration wins. */
    @Test
    void aServicesOwnSetting_wins() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("application",
                Map.of("management.endpoints.web.exposure.include", "health")));

        new ManagementDefaults().postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
    }
}
