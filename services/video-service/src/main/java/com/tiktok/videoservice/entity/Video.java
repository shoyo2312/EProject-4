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
import java.util.List;

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
                def = "{'userId': 1, 'deletedAt': 1, 'status': 1, 'visibility': 1, 'createdAt': -1}"),
        // The outbox poll runs every five seconds and matches on eventPublishedAt and deletedAt,
        // neither of which is a prefix of the two indexes above — so without this it is a full
        // collection scan plus an in-memory sort, on the one collection that only ever grows, and
        // nothing about it degrades visibly until it is already far too slow. Equality fields
        // lead, then the sort field, so Mongo takes the ordering from the index instead of sorting
        // the match set. A partial index over the unpublished rows alone would be smaller still,
        // but partialFilterExpression accepts neither an equality against null nor $exists: false.
        @CompoundIndex(name = "outbox_idx",
                def = "{'eventPublishedAt': 1, 'eventFailedAt': 1, 'deletedAt': 1, 'createdAt': 1}"),
        // The deletion poll, same reasoning as outbox_idx: equality fields lead, then the sort
        // field. eventPublishedAt is not part of it because the poll does not filter on it at
        // all — a video deleted before its publication ever went out still has to announce the
        // deletion, since media-worker's copy of the raw upload has nothing else pointing at it.
        // See VideoEventPublisher.publishPendingDeletions.
        @CompoundIndex(name = "delete_outbox_idx",
                def = "{'deleteEventPublishedAt': 1, 'deletedAt': 1}"),
        // The visibility-change poll. Only ever a handful of rows carry a non-null value at any
        // moment, but the collection it scans without this index is the largest one here.
        @CompoundIndex(name = "visibility_outbox_idx", def = "{'visibilityEventPendingAt': 1}"),
        // One upload is one video. Enforced here rather than by checking before the insert,
        // because a check followed by an insert lets two concurrent publishes of the same key
        // both pass and produce two documents, two VideoPublishedEvents, and two transcode jobs
        // off one file.
        //
        // Deliberately not scoped to undeleted rows: deleting a video does not free its raw
        // object for republishing, and the partialFilterExpression that would express that scope
        // cannot test a field for null anyway (see outbox_idx above).
        @CompoundIndex(name = "raw_file_idx", def = "{'rawFileUrl': 1}", unique = true)
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

    /** Animated hover preview. Null for videos whose transcode produced none, and for older ones. */
    private String previewUrl;

    private String hlsUrl;

    private Integer durationSeconds;

    private VideoStatus status;

    /**
     * What {@code status} was before a takedown, so a restore puts the video back where it
     * was instead of assuming PUBLISHED. Null for videos never taken down, and for those
     * taken down before this field existed.
     */
    private VideoStatus statusBeforeTakedown;

    /**
     * Why moderation took this video down — the reason string from the admin's action, carried on
     * VideoTakenDownEvent. Kept on the document rather than read back from admin-service's audit
     * log, so any listing that shows a TAKEN_DOWN video can say why without one extra call per
     * row. Cleared by a restore. Null for videos never taken down, and for those taken down
     * before this field existed.
     */
    private String takedownReason;

    /**
     * Why the last transcode attempt gave up — the message from media-worker's
     * VideoTranscodedEvent.failureReason. Shown to the uploader instead of a generic
     * "transcoding failed". Null for videos that never failed, and for ones that failed
     * before this field existed.
     */
    private String failureReason;

    /**
     * What automatic moderation found. Null for videos uploaded before this existed and for ones
     * still transcoding. See {@link VideoModeration} for why the numbers are kept.
     */
    private VideoModeration moderation;

    private VideoVisibility visibility;

    /**
     * Owner switch that stops new comments. Read by interaction-service (which owns comment data)
     * before it accepts a comment; never enforced here. Absent on documents written before this
     * field existed, which Mongo maps to {@code false} — comments on by default.
     */
    private boolean commentsDisabled;

    /**
     * Normalised at publish time and indexed because tag affinity is the only content signal
     * recommendation has: every other thing it knows about a video is engagement, which is the
     * thing it is trying to predict. Multikey — Mongo indexes each element — so a lookup by one
     * tag reads the index rather than the collection. Never null; an untagged video is empty.
     */
    @Indexed
    private List<String> tags;

    private long viewCount;

    private long likeCount;

    private long commentCount;

    private Instant eventPublishedAt;

    /**
     * Set when building this video's VideoPublishedEvent threw, which takes it out of the poll.
     *
     * <p>Without it the row parks at the head of the batch forever: the poll reads the oldest
     * hundred unpublished videos, the dispatcher skips the one it cannot serialize, and five
     * seconds later the same query returns the same row in the same first position. Retrying is
     * pointless — the same document serializes the same way every time — and every later video
     * queues behind it. Cleared only by a human who has fixed whatever made the document
     * unserializable; there is no code path that unsets it, because there is nothing a retry
     * could do differently.
     */
    private Instant eventFailedAt;

    /**
     * The second outbox slot, for this video's VideoDeletedEvent. A separate field rather than a
     * reuse of {@code eventPublishedAt}, because both events have to go out and in that order:
     * the publication is what tells consumers the video exists, and the deletion is meaningless
     * to anyone who never received it.
     */
    private Instant deleteEventPublishedAt;

    /**
     * The third outbox slot, for VideoVisibilityChangedEvent. Unlike the other two this one is
     * set and cleared repeatedly: an owner can move a video between PUBLIC, FRIENDS and PRIVATE
     * as often as they like, and every one of those has to reach the read paths that keep their
     * own copy. Null means there is nothing to announce.
     *
     * <p>It doubles as the timestamp of the change, which is what makes the event id stable
     * across a redelivery and still different for each change — see
     * {@link com.tiktok.event.video.VideoVisibilityChangedEvent#of}.
     */
    private Instant visibilityEventPendingAt;

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

    /**
     * Owner setting their own video to PUBLIC, FRIENDS or PRIVATE from the detail page. Queues the
     * announcement in the same object as the change, because a visibility this service knows about
     * and search-service does not is exactly the gap that left private videos searchable.
     */
    public void changeVisibility(VideoVisibility visibility) {
        this.visibility = visibility;
        this.visibilityEventPendingAt = Instant.now();
    }

    /** The queued visibility change has been acknowledged by the broker; nothing left to send. */
    public void markVisibilityEventPublished() {
        this.visibilityEventPendingAt = null;
    }

    /** Owner turning new comments on or off for their own video. */
    public void changeCommentsDisabled(boolean commentsDisabled) {
        this.commentsDisabled = commentsDisabled;
    }

    /**
     * Transcode succeeded. This does not publish the video: it goes to PENDING_MODERATION and
     * waits for a verdict, so nothing reaches a viewer before it has been screened. A video that
     * stops here is a video moderation never answered about, which is a bug worth being able to
     * see rather than one that silently publishes.
     */
    public void markTranscoded(String thumbnailUrl, String previewUrl, String hlsUrl, Integer durationSeconds) {
        this.thumbnailUrl = thumbnailUrl;
        this.previewUrl = previewUrl;
        this.hlsUrl = hlsUrl;
        this.durationSeconds = durationSeconds;
        this.failureReason = null; // a redelivered publish must not leave a prior FAILED reason on the doc
        applyOutcome(VideoStatus.PENDING_MODERATION);
    }

    /**
     * The moderation verdict, and the status that follows from it.
     *
     * <p>REJECTED is the model's own removal and stays distinct from TAKEN_DOWN, which is an
     * admin's. An admin overturning either goes through {@link #markRestored()}.
     */
    public void applyModeration(VideoModeration moderation) {
        this.moderation = moderation;
        applyOutcome(switch (moderation.getVerdict()) {
            case APPROVED -> VideoStatus.PUBLISHED;
            case REVIEW -> VideoStatus.PENDING_REVIEW;
            case REJECTED -> VideoStatus.REJECTED;
        });
    }

    public void markFailed(String reason) {
        this.failureReason = reason;
        applyOutcome(VideoStatus.FAILED);
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
     *
     * <p>The automatic verdict goes through here too, for the same reason and one more: an admin
     * who took a video down while the classifier was still looking at it has already made the
     * decision the classifier was about to make, and an APPROVED landing afterwards must not undo
     * it.
     */
    private void applyOutcome(VideoStatus outcome) {
        if (this.status == VideoStatus.TAKEN_DOWN) {
            this.statusBeforeTakedown = outcome;
        } else {
            this.status = outcome;
        }
    }

    public void markTakenDown(String reason) {
        // Guarded so a repeated takedown doesn't record TAKEN_DOWN as the state to restore to.
        if (this.status != VideoStatus.TAKEN_DOWN) {
            this.statusBeforeTakedown = this.status;
        }
        this.takedownReason = reason;
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
        this.takedownReason = null; // a restored video is not down, so nothing should still say why it was
    }

    public void markEventPublished() {
        this.eventPublishedAt = Instant.now();
    }

    public void markEventFailed() {
        this.eventFailedAt = Instant.now();
    }

    public void markDeleteEventPublished() {
        this.deleteEventPublishedAt = Instant.now();
    }
}
