package com.tiktok.kafka.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "tiktok.kafka.outbox")
public class OutboxProperties {

    /**
     * How long a publish poll waits for the broker to acknowledge a record before giving up and
     * leaving the row for the next poll. Kept well under the producer's own delivery.timeout.ms
     * (120s default) so a broker outage doesn't pin the scheduler thread — and, in the JPA
     * services, hold the surrounding transaction open — for minutes at a time.
     */
    private Duration ackTimeout = Duration.ofSeconds(30);

    public Duration getAckTimeout() {
        return ackTimeout;
    }

    public void setAckTimeout(Duration ackTimeout) {
        this.ackTimeout = ackTimeout;
    }
}
