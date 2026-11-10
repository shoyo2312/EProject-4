package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.Story;
import com.tiktok.storyservice.entity.StoryMediaType;
import com.tiktok.storyservice.entity.StoryModeration;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryVisibility;

import java.time.Instant;
import java.util.List;

/**
 * Field-scoped updates. {@code save()} writes the whole document under {@code @Version}, so two
 * concurrent writers — two viewers, a viewer and the verdict — either lose each other's change or
 * fail on the version; none of these needs anything the document already holds.
 */
public interface StoryRepositoryCustom {

    void incrementViewCount(String storyId);

    /**
     * Moves a story out of PENDING_MODERATION. The match on the current status is the idempotency
     * claim for the verdict consumer: a redelivered verdict, or one for a story that is gone,
     * matches nothing.
     *
     * <p>Going ACTIVE also stamps {@code activatedAt}, the moment the story becomes announceable.
     *
     * @param expiresAt null leaves the expiry as it is
     * @return whether this call made the transition
     */
    default boolean decide(String storyId, StoryStatus to, Instant expiresAt, Integer durationSeconds,
                           StoryModeration moderation) {
        return decide(storyId, to, expiresAt, durationSeconds, moderation, null, null);
    }

    /**
     * @param mediaKey  where media-worker stored the story rendered from its edits, served from now
     *                  on; null leaves the media as posted
     * @param mediaType the rendered media's type, set with {@code mediaKey}
     */
    boolean decide(String storyId, StoryStatus to, Instant expiresAt, Integer durationSeconds,
                   StoryModeration moderation, String mediaKey, StoryMediaType mediaType);

    void markEventPublished(String storyId, Instant at);

    /**
     * ACTIVE stories visible beyond their owner that nobody has been told about yet, oldest
     * activation first — the poll behind StoryActivatedEvent.
     */
    List<Story> findAnnounceable(Instant now, int limit);

    void markAnnounced(String storyId, Instant at);

    void updateVisibility(String storyId, StoryVisibility visibility);

    /** Soft delete; the TTL removes the document once it expires either way. */
    void markDeleted(String storyId, Instant at);
}
