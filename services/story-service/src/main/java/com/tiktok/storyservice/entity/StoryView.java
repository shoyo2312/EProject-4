package com.tiktok.storyservice.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One row per (storyId, viewerId) — the unique compound index makes a repeat view a
 * no-op so {@link Story#incrementViewCount()} only ever fires once per viewer.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "story_views")
@CompoundIndexes({
        @CompoundIndex(name = "story_viewer_idx", def = "{'storyId': 1, 'viewerId': 1}", unique = true),
        // "Which of these stories has this viewer seen" — one query per tray.
        @CompoundIndex(name = "viewer_story_idx", def = "{'viewerId': 1, 'storyId': 1}"),
        @CompoundIndex(name = "story_viewed_idx", def = "{'storyId': 1, 'viewedAt': -1}")
})
public class StoryView {

    @Id
    private String id;

    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;

    private String storyId;

    private Long viewerId;

    @CreatedDate
    private Instant viewedAt;
}
