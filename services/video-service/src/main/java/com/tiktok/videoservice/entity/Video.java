package com.tiktok.videoservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Mongo has no multi-document transactions on this single-node deployment, so the
 * outbox pattern is adapted per-aggregate: {@code eventPublishedAt} marks whether this
 * document's VideoPublishedEvent has been sent, and is flipped in the same atomic
 * single-document write as the rest of the field — no separate outbox collection needed.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "videos")
@CompoundIndexes({
        @CompoundIndex(name = "feed_idx", def = "{'status': 1, 'visibility': 1, 'deletedAt': 1, 'createdAt': -1}"),
        // Profile listing. feed_idx cannot serve it — userId is not a prefix there. Every field
        // ahead of createdAt is matched by equality, so both the owner variant (userId +
        // deletedAt) and the stranger variant (all four) read this index and take the sort from
        // it rather than sorting the match set in memory, which a prolific uploader would
        // eventually grow past Mongo's 32MB in-memory sort limit.
        @CompoundIndex(name = "user_videos_idx",
                def = "{'userId': 1, 'deletedAt': 1, 'status': 1, 'visibility': 1, 'createdAt': -1}")
})
public class Video {

    @Id
    private String id;

    @Indexed
    private Long userId;

    private String title;

    private String description;

    private String rawFileUrl;

    private String thumbnailUrl;

    private String hlsUrl;

    private Integer durationSeconds;

    private VideoStatus status;

    /**
     * What {@code status} was before a takedown, so a restore puts the video back where it
     * was instead of assuming PUBLISHED. Null for videos never taken down, and for those
     * taken down before this field existed.
     */
    private VideoStatus statusBeforeTakedown;

    private VideoVisibility visibility;

    private long viewCount;

    private long likeCount;

    private long commentCount;

    private Instant eventPublishedAt;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    private Instant deletedAt;

    @Version
    private Long version;

    public static String newId() {
        return String.valueOf(SnowflakeIdGenerator.nextId());
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
    }

    public void markPublished(String thumbnailUrl, String hlsUrl, Integer durationSeconds) {
        this.thumbnailUrl = thumbnailUrl;
        this.hlsUrl = hlsUrl;
        this.durationSeconds = durationSeconds;
        this.status = VideoStatus.PUBLISHED;
    }

    public void markFailed() {
        this.status = VideoStatus.FAILED;
    }

    public void markTakenDown() {
        // Guarded so a repeated takedown doesn't record TAKEN_DOWN as the state to restore to.
        if (this.status != VideoStatus.TAKEN_DOWN) {
            this.statusBeforeTakedown = this.status;
        }
        this.status = VideoStatus.TAKEN_DOWN;
    }

    /**
     * A video taken down while still PROCESSING or FAILED has no hlsUrl, so restoring it as
     * PUBLISHED would put an unplayable entry on the feed. It goes back to whatever it was;
     * the transcode result, if one arrives later, publishes it normally.
     */
    public void markRestored() {
        this.status = this.statusBeforeTakedown == null ? VideoStatus.PUBLISHED : this.statusBeforeTakedown;
        this.statusBeforeTakedown = null;
    }

    public void markEventPublished() {
        this.eventPublishedAt = Instant.now();
    }
}
