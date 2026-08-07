package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.Video;

/**
 * Field-scoped writes for every path that changes an existing Video.
 *
 * <p>Why not {@code save()}: likeCount and commentCount are maintained with {@code $inc} through
 * {@code MongoTemplate.updateFirst(..., Video.class)}, and for a versioned entity Spring Data
 * bumps {@code @Version} on those updates too (verified: insert leaves version 0, one {@code $inc}
 * leaves it 1 — only a raw driver call bypasses it). Any concurrent load-mutate-{@code save()}
 * therefore writes with a filter on the version it read, no longer matches, and fails with
 * {@code OptimisticLockingFailureException}. A single like landing mid-operation is enough.
 *
 * <p>What that cost at each call site, before this existed:
 * <ul>
 *   <li>transcode result and moderation takedown/restore — the consumer threw, the claim was
 *       released, and kafka-lib retried three times and shipped the event to the DLT. The video
 *       stayed PROCESSING, or stayed visible after a takedown. Worst for exactly the videos that
 *       matter: the ones being liked fast enough to keep losing the race.</li>
 *   <li>the outbox flag — the throw escapes {@code OutboxDispatcher.dispatch}, which only handles
 *       the ack failures, so it propagates out of the scheduled poll <em>after</em> the record was
 *       already sent. eventPublishedAt never got set, so the next poll five seconds later sent it
 *       again, and again, for as long as the likes kept coming.</li>
 *   <li>soft delete — a 500 on DELETE, which a retry usually got past.</li>
 * </ul>
 *
 * <p>Each method here writes only the fields its own operation owns and never conditions the
 * write on a previously read version, so counter increments and status changes stop competing.
 * Splitting by operation instead of exposing one generic update keeps that ownership visible at
 * the call site.
 */
public interface VideoRepositoryCustom {

    /** Transcode succeeded: status plus the media fields it produced. */
    void updateTranscodeResult(Video video);

    /** Status alone — transcode failure, which has no media fields to write. */
    void updateStatus(Video video);

    /** Moderation takedown/restore: the status and the state to restore to. */
    void updateModerationStatus(Video video);

    /** Outbox flag, set once the broker acknowledges the VideoPublishedEvent. */
    void updateEventPublished(Video video);

    /** Soft delete. */
    void updateSoftDeleted(Video video);
}
