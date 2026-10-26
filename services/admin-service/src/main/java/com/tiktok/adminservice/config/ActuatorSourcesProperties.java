package com.tiktok.adminservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Other services' actuator, open on the internal network without a token (common-lib
 * ManagementDefaults). The console's System screens show what each service reports about itself
 * there, rather than a copy configured here that could say something else.
 *
 * @param timeout        connect and read, each. A screen waits on the slowest service, all asked at once
 * @param outboxes       services with an outbox besides this one, by name: kafka-lib's backlog gauges,
 *                       from /actuator/prometheus. A service that gets an outbox is added here
 * @param textModeration services that screen text with moderation-lib and act on it: its enforcement
 *                       flags, from /actuator/info
 */
@ConfigurationProperties(prefix = "admin.actuator")
public record ActuatorSourcesProperties(
        @DefaultValue("2s") Duration timeout,
        @DefaultValue Map<String, URI> outboxes,
        @DefaultValue Map<String, URI> textModeration
) {
}
