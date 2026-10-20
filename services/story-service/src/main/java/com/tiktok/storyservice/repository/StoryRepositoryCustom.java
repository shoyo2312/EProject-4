package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.StoryModeration;
import com.tiktok.storyservice.entity.StoryStatus;
import com.tiktok.storyservice.entity.StoryVisibility;

import java.time.Instant;

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
     * @param expiresAt null leaves the expiry as it is
     * @return whether this call made the transition
     */
    boolean decide(String storyId, StoryStatus to, Instant expiresAt, Integer durationSeconds,
                   StoryModeration moderation);

    void markEventPublished(String storyId, Instant at);

    void updateVisibility(String storyId, StoryVisibility visibility);
}
