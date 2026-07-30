package com.tiktok.event;

import java.time.Instant;

/**
 * Marker implemented by every Kafka event record so consumers can log/route generically.
 */
public interface DomainEvent {
    String eventId();

    Instant occurredAt();
}
