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
        // _id is in the key, and not as decoration: the feed pages by keyset on (createdAt, _id)
        // — see VideoRepositoryCustom.findFeedPage — and Mongo can only take a compound sort from
        // an index carrying every field of that sort. Without _id here the range would still read
        // the index but the sort would become a blocking in-memory sort of the whole match set,
        // which is the same 32MB ceiling the profile index below was shaped to stay under.
        @CompoundIndex(name = "feed_idx",
                def = "{'status': 1, 'visibility': 1, 'deletedAt': 1, 'createdAt': -1, '_id': -1}"),
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
        applyTranscodeOutcome(VideoStatus.PUBLISHED);
    }

    public void markFailed() {
        applyTranscodeOutcome(VideoStatus.FAILED);
    }

    /**
     * A takedown outlives the transcode that was still running when it landed: transcoding takes
     * minutes, so a moderator acting on a freshly uploaded video is routinely overtaken by its own
     * result. Writing that result to {@code status} would put the video straight back on the feed
     * with nothing left to say it had been removed, and would leave {@code statusBeforeTakedown}
     * pointing at PROCESSING, so a later restore lands on a state the video is no longer in.
     *
     * <p>While the video is down the outcome is recorded as what a restore should return to, and
     * {@code status} stays TAKEN_DOWN. The media fields are written either way — they describe the
     * file, not its moderation state, and the video needs them the moment it comes back.
     */
    private void applyTranscodeOutcome(VideoStatus outcome) {
        if (this.status == VideoStatus.TAKEN_DOWN) {
            this.statusBeforeTakedown = outcome;
        } else {
            this.status = outcome;
        }
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
