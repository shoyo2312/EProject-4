package com.tiktok.storyservice.entity;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.event.video.ModerationVerdict;
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

import java.time.Duration;
import java.time.Instant;

/**
 * {@code expiresAt} carries a Mongo TTL index (expireAfterSeconds = 0) so MongoDB physically
 * removes the document once it passes, regardless of {@code deletedAt} — this hard removal is the
 * intended ephemeral-content behavior for stories, not a violation of the soft-delete rule, which
 * still governs manual deletes before expiry ({@code StoryRepositoryCustom.markDeleted}).
 *
 * <p>A new story gets a {@link #PENDING_CEILING} rather than the 24h display window: the window
 * starts when moderation approves it ({@code StoryRepositoryCustom.decide}), so screening time does
 * not eat into it, and the ceiling removes a story whose verdict never arrives instead of keeping
 * it forever. HELD and REJECTED stories keep the ceiling.
 *
 * <p>The outbox is a flag on the document, as on video-service's Video: this Mongo deployment
 * has no replica set, so there is no transaction to put a separate outbox row in.
 */
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "stories")
@CompoundIndexes({
        @CompoundIndex(name = "author_status_idx", def = "{'userId': 1, 'status': 1, 'expiresAt': 1}")
})
public class Story {

    public static final Duration PENDING_CEILING = Duration.ofHours(48);

    @Id
    private String id;

    @Indexed
    private Long userId;

    /** Object key under {@code stories/}; never served directly, only through a presigned URL. */
    @Indexed(unique = true)
    private String mediaKey;

    private StoryMediaType mediaType;

    private String caption;

    private StoryStatus status;

    private StoryVisibility visibility;

    /** A video's probed length, from the moderation verdict; null for images and until then. */
    private Integer durationSeconds;

    private StoryModeration moderation;

    /** The caption's verdict, carried to media-worker on the created event. */
    private ModerationVerdict textVerdict;

    private long viewCount;

    @CreatedDate
    private Instant createdAt;

    @Indexed(expireAfterSeconds = 0)
    private Instant expiresAt;

    private Instant deletedAt;

    /** Set once the broker acknowledged StoryCreatedEvent — the outbox flag. */
    private Instant eventPublishedAt;

    @Version
    private Long version;

    public static String newId() {
        return String.valueOf(SnowflakeIdGenerator.nextId());
    }

    public static Story pending(String id, Long userId, String mediaKey, StoryMediaType mediaType, String caption,
                                StoryVisibility visibility, ModerationVerdict textVerdict, Instant now) {
        return Story.builder()
                .id(id)
                .userId(userId)
                .mediaKey(mediaKey)
                .mediaType(mediaType)
                .caption(caption)
                .status(StoryStatus.PENDING_MODERATION)
                .visibility(visibility)
                .textVerdict(textVerdict)
                .viewCount(0)
                .createdAt(now)
                .expiresAt(now.plus(PENDING_CEILING))
                .build();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

}
