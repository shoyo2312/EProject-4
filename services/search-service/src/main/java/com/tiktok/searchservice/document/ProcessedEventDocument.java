package com.tiktok.searchservice.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.time.Instant;

/**
 * Inbox record for Kafka consumers on this service, keyed by the event's own eventId
 * (no separate unique-index field needed, unlike the Mongo services' processed_events).
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "processed_events")
public class ProcessedEventDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String eventType;

    @Field(type = FieldType.Date, format = org.springframework.data.elasticsearch.annotations.DateFormat.date_hour_minute_second_millis)
    private Instant processedAt;
}
