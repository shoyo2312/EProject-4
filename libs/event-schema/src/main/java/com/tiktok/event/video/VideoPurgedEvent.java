package com.tiktok.event.video;

import com.tiktok.event.DomainEvent;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/**
 * A deleted video's trash window ran out: erase its media. Published on the same topic and under
 * the same key as {@link VideoDeletedEvent}, but a separate event because the two have different
 * audiences and, deliberately, different timing.
 *
 * <p>{@link VideoDeletedEvent} goes out the moment the owner deletes and is what takes the video
 * out of search results and the recommendation feed. This one goes out {@code video.trash.retention}
 * later (30 days by default) and is what makes media-worker erase the objects in MinIO. Folding the
 * two into one delayed event left deleted videos searchable for the whole trash window; folding
 * them into one immediate event left nothing for a moderator to review once the owner had removed
 * it. Only media-worker acts on this; every index consumer ignores the type.
 */
public record VideoPurgedEvent(
        String eventId,
        Instant occurredAt,
        String videoId,
        Long userId,
        // media-worker is the only party that knows the bucket, and this event is the only thing
        // that still names the raw key — once the purge is done nothing looks the document up again.
        String rawFileUrl
) implements DomainEvent {

    /** Derived from the videoId, for the same reason {@link VideoDeletedEvent#of} derives its own. */
    public static VideoPurgedEvent of(String videoId, Long userId, String rawFileUrl) {
        String eventId = UUID.nameUUIDFromBytes(
                ("VideoPurgedEvent:" + videoId).getBytes(StandardCharsets.UTF_8)).toString();
        return new VideoPurgedEvent(eventId, Instant.now(), videoId, userId, rawFileUrl);
    }
}
