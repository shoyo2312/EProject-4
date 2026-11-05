package com.tiktok.event.story;

import com.tiktok.event.DomainEvent;
import com.tiktok.event.video.ModerationVerdict;
import com.tiktok.event.video.VideoPublishedEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * A story was posted and waits for its media to be screened. Published by story-service through
 * its outbox and consumed by media-worker, which answers with {@link StoryModerationCompletedEvent}.
 *
 * <p>Carries the object key rather than a URL: story media lives under {@code stories/}, which is
 * never readable anonymously, so media-worker reads it with its own credentials.
 *
 * @param mediaType    {@code IMAGE} or {@code VIDEO}
 * @param textVerdict  the caption's verdict from moderation-lib — REVIEW holds an otherwise approved
 *                     story; null when there was no caption
 * @param edit         the edits made in the app's editor, the same recipe a video's publication
 *                     carries: media-worker renders them before screening, and screens what it
 *                     rendered. Null for a story posted as it was shot. On an image the segments
 *                     are ignored
 * @param photoSeconds how long an image story with music plays once rendered into a clip; null
 *                     otherwise
 */
public record StoryCreatedEvent(
        String eventId,
        Instant occurredAt,
        String storyId,
        Long userId,
        String mediaKey,
        String mediaType,
        ModerationVerdict textVerdict,
        VideoPublishedEvent.Edit edit,
        Double photoSeconds
) implements DomainEvent {

    /** Posted as it was shot. */
    public static StoryCreatedEvent of(String storyId, Long userId, String mediaKey, String mediaType,
                                       ModerationVerdict textVerdict) {
        return of(storyId, userId, mediaKey, mediaType, textVerdict, null, null);
    }

    public static StoryCreatedEvent of(String storyId, Long userId, String mediaKey, String mediaType,
                                       ModerationVerdict textVerdict, VideoPublishedEvent.Edit edit,
                                       Double photoSeconds) {
        return new StoryCreatedEvent(UUID.randomUUID().toString(), Instant.now(), storyId, userId,
                mediaKey, mediaType, textVerdict, edit, photoSeconds);
    }
}
