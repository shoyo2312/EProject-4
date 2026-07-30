package com.tiktok.storyservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * {@code expiresAt} carries a Mongo TTL index (expireAfterSeconds = 0) so MongoDB physically
 * removes the document ~24h after creation regardless of {@code deletedAt} — this hard removal
 * is the intended ephemeral-content behavior for stories, not a violation of the soft-delete rule,
 * which still governs manual deletes before expiry via {@link #markDeleted()}.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stories")
@CompoundIndexes({
        @CompoundIndex(name = "author_feed_idx", def = "{'userId': 1, 'deletedAt': 1, 'createdAt': -1}")
})
public class Story {

    @Id
    private String id;

    @Indexed
    private Long userId;

    private String mediaUrl;

    private StoryMediaType mediaType;

    private String caption;

    private long viewCount;

    @CreatedDate
    private Instant createdAt;

    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;

    private Instant deletedAt;

    @Version
    private Long version;

    public static String newId() {
        return String.valueOf(SnowflakeIdGenerator.nextId());
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    public boolean isVisible() {
        return !isDeleted() && !isExpired();
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
    }

    public void incrementViewCount() {
        this.viewCount++;
    }
}
