package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

public record VideoPublishedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long userId,
        String title,
        String rawFileUrl
) implements DomainEvent {

    /**
     * The eventId is derived from the videoId rather than drawn at random, because this event is
     * produced by an outbox poll that can legitimately send the same video twice: an
     * acknowledgement that times out leaves the row unmarked even when the broker did receive the
     * record, and every replica runs the poll. A fresh UUID on the retry makes the duplicate
     * unrecognisable to the consumers that deduplicate on eventId — recommendation-service scores
     * the video a second time, analytics-service stores a second engagement row — so the
     * identifier has to name the fact, and a video is published exactly once.
     *
     * <p>Type 3 UUID rather than the raw id, because consumers keep this in the same column as
     * identifiers from every other producer, all of which are UUIDs.
     */
    public static VideoPublishedEvent of(String videoId, Long userId, String title, String rawFileUrl) {
        String eventId = UUID.nameUUIDFromBytes(
                ("VideoPublishedEvent:" + videoId).getBytes(StandardCharsets.UTF_8)).toString();
        return new VideoPublishedEvent(eventId, Instant.now(), videoId, userId, title, rawFileUrl);
    }
}
