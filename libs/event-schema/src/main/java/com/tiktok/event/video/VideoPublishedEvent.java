package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VideoPublishedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long userId,
        String title,
        // Carried for the same reason as tags: search-service builds its index from this event
        // and has no read path into video-service's Mongo, so a description that stays here is a
        // description no query can ever match on.
        String description,
        String rawFileUrl,
        // Who is allowed to see the video: PUBLIC, FRIENDS or PRIVATE, as video-service's
        // VideoVisibility names them — or FOLLOWERS, a PUBLIC video whose owner's account is
        // private. Anything but PUBLIC is hidden on every read path that keeps its own copy.
        // Carried because search-service builds its own read path off this event and never calls
        // video-service, so without it a PRIVATE video is returned to any anonymous search —
        // status alone says the video finished moderation, not who may see it. Null only on events
        // from a producer older than this field; consumers read that as PUBLIC, which is what the
        // producer of the day only ever published.
        String visibility,
        // Carried on the event because recommendation-service has no read path into video-service's
        // Mongo, and tags are the only content feature a candidate generator has to work with —
        // everything else it knows about a video is engagement, which is what it is trying to
        // predict. Never null: an untagged video is an empty list, so no consumer needs a guard.
        List<String> tags,
        // What text moderation made of the title, description and tags at publish time: APPROVED,
        // or REVIEW when a person should look first. Never REJECTED — a caption that bad is refused
        // before the video exists. media-worker carries it onto VideoTranscodedEvent and folds it
        // into the moderation verdict, so every consumer of that verdict holds the video the same
        // way. Null from a producer older than this field, or with text moderation not enforced;
        // either way it changes nothing.
        ModerationVerdict textVerdict
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
    public static VideoPublishedEvent of(
            String videoId, Long userId, String title, String description, String rawFileUrl,
            String visibility, List<String> tags, ModerationVerdict textVerdict) {
        String eventId = UUID.nameUUIDFromBytes(
                ("VideoPublishedEvent:" + videoId).getBytes(StandardCharsets.UTF_8)).toString();
        return new VideoPublishedEvent(eventId, Instant.now(), videoId, userId, title, description,
                rawFileUrl, visibility, tags == null ? List.of() : List.copyOf(tags), textVerdict);
    }

    /** Without a text verdict, for callers with nothing to say about the caption. */
    public static VideoPublishedEvent of(
            String videoId, Long userId, String title, String description, String rawFileUrl,
            String visibility, List<String> tags) {
        return of(videoId, userId, title, description, rawFileUrl, visibility, tags, null);
    }
}
