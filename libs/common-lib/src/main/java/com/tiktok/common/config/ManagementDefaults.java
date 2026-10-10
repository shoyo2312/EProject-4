package com.tiktok.common.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * The actuator settings every service shares, as defaults its own configuration can still
 * override: health with the liveness and readiness groups a container probe asks for, info, and
 * the Prometheus scrape — consumer lag, the DLT counter kafka-lib keeps, request timings — with
 * every meter tagged by service. One place rather than the same block in thirteen application.yml
 * files, kept apart by nothing but copy-paste.
 *
 * <p>Nothing here opens the endpoints to anyone: the services run on an internal network, and
 * security-lib, auth-service and the gateway each say who may reach them.
 */
public class ManagementDefaults implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Last, so it loses to every other source: application.yml, profiles, the environment.
        environment.getPropertySources().addLast(new MapPropertySource("tiktokManagementDefaults", Map.of(
                "management.endpoints.web.exposure.include", "health,info,prometheus",
                "management.endpoint.health.probes.enabled", "true",
                "management.metrics.tags.application", "${spring.application.name:unknown}")));
    }
}
