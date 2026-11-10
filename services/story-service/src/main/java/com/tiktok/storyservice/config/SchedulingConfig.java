package com.tiktok.storyservice.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Runs the outbox poll in StoryEventPublisher. */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
