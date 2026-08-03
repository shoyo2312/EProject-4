package com.tiktok.videoservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Inbox record for Kafka consumers on this service — mirrors the Postgres inbox_events
 * table used by JPA services, adapted to Mongo (own collection instead of a shared schema).
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "processed_events")
public class ProcessedEvent {

    @Id
    private String id;

    @Indexed(unique = true)
    private String eventId;

    private String eventType;

    /**
     * TTL far longer than any realistic Kafka redelivery window (retention plus a stuck
     * consumer), but bounded — without it this collection grows for the life of the service
     * to guard against replays that can no longer happen.
     */
    @CreatedDate
    @Indexed(expireAfter = "30d")
    private Instant processedAt;
}
