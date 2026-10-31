package com.tiktok.recommendationservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * The randomness the feed perturbs its ordering with. A bean rather than a static call so a test
 * can hand the service a generator whose output it controls.
 */
@Configuration
public class FeedExplorationConfig {

    /** Per-thread underneath, so concurrent feed requests do not contend on one generator. */
    @Bean
    public RandomGenerator feedRandom() {
        return () -> ThreadLocalRandom.current().nextLong();
    }
}
