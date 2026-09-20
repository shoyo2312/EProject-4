package com.tiktok.notificationservice.entity;

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
     * Claims are only useful for as long as the broker can still redeliver the event they guard
     * (retention is 7 days), so they expire well after that rather than accumulating forever —
     * same 30 days video-service keeps. This collection has no business meaning and no
     * {@code deletedAt}: its whole problem is row count, which a soft delete would not solve.
     */
    @CreatedDate
    @Indexed(expireAfter = "30d")
    private Instant processedAt;
}
