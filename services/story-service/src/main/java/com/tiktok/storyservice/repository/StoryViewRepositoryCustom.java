package com.tiktok.storyservice.repository;

import com.tiktok.storyservice.entity.StoryReaction;

import java.time.Instant;
import java.util.Map;

public interface StoryViewRepositoryCustom {

    /**
     * Sets the viewer's reaction on their view row, creating the row when there is none yet — a
     * reaction can arrive before, or instead of, the view being counted. One upsert, no read first.
     *
     * @return whether the row was created by this call, which is when the story's view count moves
     */
    boolean upsertReaction(String storyId, Long viewerId, StoryReaction reaction, Instant expiresAt, Instant now);

    /** Removes the viewer's reaction; the view row stays. A no-op when there is none. */
    void clearReaction(String storyId, Long viewerId);

    /** How many viewers reacted each way; reactions nobody gave are absent. */
    Map<StoryReaction, Long> countReactions(String storyId);
}
