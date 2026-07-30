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
        @CompoundIndex(name = "feed_idx", def = "{'status': 1, 'visibility': 1, 'deletedAt': 1, 'createdAt': -1}")
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

    public void markEventPublished() {
        this.eventPublishedAt = Instant.now();
    }
}
