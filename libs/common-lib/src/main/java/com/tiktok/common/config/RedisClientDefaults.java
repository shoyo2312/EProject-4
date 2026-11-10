package com.tiktok.common.config;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

/**
 * How long a Redis call may take before it fails, for every service that talks to Redis. Every
 * read side here fails open when Redis is down — revocation, rate limits, strikes, caches — but
 * Lettuce on its own waits a minute per command for a Redis that stopped answering without
 * closing the connection, so "fail open" meant a minute per request and request threads running
 * out. A healthy Redis answers in well under a millisecond; a second is an outage.
 *
 * <p>Defaults only, like {@link ManagementDefaults}: a service that needs longer for a heavy
 * command sets its own {@code spring.data.redis.timeout}.
 */
public class RedisClientDefaults implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Last, so it loses to every other source: application.yml, profiles, the environment.
        environment.getPropertySources().addLast(new MapPropertySource("tiktokRedisClientDefaults", Map.of(
                "spring.data.redis.timeout", "1s",
                "spring.data.redis.connect-timeout", "1s")));
    }
}
