package com.tiktok.videoservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is off under the test profile: VideoEventPublisher polls every few seconds, and
 * in a @SpringBootTest that meant it fired against a Kafka that isn't there, logging errors
 * unrelated to the test and holding the Surefire fork open past the run.
 */
@Configuration
@Profile("!test")
@EnableScheduling
public class SchedulingConfig {
}
