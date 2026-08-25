package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.entity.Video;
import com.tiktok.videoservice.entity.VideoStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

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

    /**
     * One page of the public feed, positioned by keyset rather than by {@code skip}.
     *
     * <p>{@code Page} + {@code skip} was the wrong shape for an infinite feed twice over: the count
     * query behind {@code Page} re-walks the whole match set on every request to produce a total no
     * feed screen displays, and {@code skip(n)} makes Mongo step over n documents before returning
     * anything, so the cost of page 500 is paid in full every time someone scrolls that far. A
     * range on the sort key starts where the last page ended and reads only what it returns.
     *
     * <p>Derived queries cannot express the {@code (createdAt < c) OR (createdAt = c AND _id < id)}
     * that the tiebreak needs, which is why this lives here and not on {@link VideoRepository}.
     *
     * <p>One method rather than two, because the Following feed is this same page with an author
     * filter in front of it: same status+visibility rule, same keyset, same tiebreak. A second copy
     * would be a second place to keep those three in step.
     *
     * @param userIds         restrict to these authors — the Following feed — or null for the
     *                        public feed, which is every author
     * @param beforeCreatedAt null for the first page, together with {@code beforeId}
     * @param limit           ask for one more than the page holds — a row beyond it is how the
     *                        caller learns there is a next page without counting anything
     */
    List<Video> findFeedPage(Collection<Long> userIds, Instant beforeCreatedAt, String beforeId, int limit);

    /**
     * Transcode succeeded: the media fields it produced, plus where the outcome was recorded.
     *
     * @param expectedStatus the status read before the change was applied — see
     *                       {@link #updateStatus} for what conditioning on it prevents
     * @return whether the write landed
     */
    boolean updateTranscodeResult(Video video, VideoStatus expectedStatus);

    /**
     * The status pair alone, with no media fields to write: a failed transcode, and a moderation
     * takedown or restore.
     *
     * <p>One method for all three, not two identical ones. They write the same pair for the same
     * reason — {@code statusBeforeTakedown} is where a transcode outcome goes while the video is
     * down, and where a takedown parks the state a restore returns to — so the second copy was a
     * second body to keep in step with {@link Video} for nothing. What each call means is already
     * on the line above it at the call site: {@code markFailed()}, {@code markTakenDown()},
     * {@code markRestored()}.
     *
     * <p>Conditioned on the status the caller read, because these three race each other across
     * two Kafka topics and two listener threads. Transcoding takes minutes, so a moderator's
     * takedown routinely lands while a transcode result is in flight; an unconditional write
     * lets whichever arrives last win, which puts a taken-down video back on the feed with
     * nothing left to say it was ever removed.
     *
     * @param expectedStatus the status read before the change was applied
     * @return false when the status moved underneath — re-read and re-apply, do not retry the
     *         same write
     */
    boolean updateStatus(Video video, VideoStatus expectedStatus);

    /** Outbox flag, set once the broker acknowledges the VideoPublishedEvent. */
    void updateEventPublished(Video video);

    /** Outbox flag, set once the broker acknowledges the VideoDeletedEvent. */
    void updateDeleteEventPublished(Video video);

    /**
     * Parks a row whose event could not be built, taking it out of the poll — see
     * {@link Video#markEventFailed}.
     */
    void updateEventFailed(Video video);

    /**
     * The profile header's totals: how many videos, and the likes and views summed over them.
     *
     * <p>Aggregated on read rather than denormalised onto the user, because likeCount already
     * moves on every like through a Kafka consumer — a second running total would be a second
     * thing to keep in step with it, and the two would drift the first time one of the two
     * writes failed.
     *
     * <p>The match is the same pair {@code listByUser} draws, so the number under the avatar and
     * the grid below it are describing the same set of videos: the owner's own hidden videos
     * count for the owner, and for nobody else.
     *
     * @param includeHidden true for the owner's own view — count PROCESSING and PRIVATE videos too
     */
    UserVideoStats sumUserVideoStats(Long userId, boolean includeHidden);

    /** Soft delete. */
    void updateSoftDeleted(Video video);
}
