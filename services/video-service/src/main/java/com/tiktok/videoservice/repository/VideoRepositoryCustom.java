package com.tiktok.videoservice.repository;

import com.tiktok.videoservice.dto.response.DailyVideoStatsResponse;
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
     * The admin console's video listing: every video regardless of owner, status or visibility.
     *
     * <p>Not expressible as a derived query — both filters are optional, which is four
     * combinations, and the title match is a case-insensitive substring rather than an equality.
     *
     * <p>Soft-deleted videos are included — this is the one listing the admin console has to
     * account for them on, since {@code getById} only answers for a deleted video if you already
     * know its id. {@code deletedAt} on the response is what tells a row apart from a live one.
     *
     * <p>No index serves this well: the sort is on createdAt while the filters are a status
     * equality and a regex, so with a status given Mongo can take bounds from {@code feed_idx} but
     * not the sort. Acceptable only because this is one screen with one admin behind it — do not
     * reuse it on a user-facing path.
     *
     * <p>{@code term} and {@code ownerIds} are one search expressed two ways, so they are OR'd:
     * this collection stores the owner's id and never their handle, which means a search for a
     * handle can only arrive here as the ids it resolved to. AND-ing them would ask for a video
     * whose title also contains the handle, which is nothing.
     *
     * @param status   null for every status
     * @param term     case-insensitive substring of the title, or null for no title filter
     * @param ownerIds videos by these owners match too, or null/empty for no owner filter
     * @param deleted  null for no filter on it (the default — both live and deleted rows match);
     *                 {@code true} for deleted rows only; {@code false} for live rows only
     */
    org.springframework.data.domain.Page<Video> findForAdmin(
            VideoStatus status, String term, java.util.Collection<Long> ownerIds,
            Boolean deleted, org.springframework.data.domain.Pageable pageable);

    /**
     * Transcode succeeded: the media fields it produced, plus where the outcome was recorded.
     *
     * @param expectedStatus the status read before the change was applied — see
     *                       {@link #updateStatus} for what conditioning on it prevents
     * @return whether the write landed
     */
    boolean updateTranscodeResult(Video video, VideoStatus expectedStatus);

    /**
     * The status pair alone, with no media fields to write: a moderation takedown or restore.
     *
     * <p>One method for both, not two identical ones. They write the same pair for the same
     * reason — {@code statusBeforeTakedown} is where a takedown parks the state a restore returns
     * to — so the second copy was a second body to keep in step with {@link Video} for nothing.
     * What each call means is already on the line above it at the call site: {@code markTakenDown(reason)},
     * {@code markRestored()}. A failed transcode writes the same pair but owns {@code failureReason}
     * on top of it — see {@link #updateFailed}.
     *
     * <p>Conditioned on the status the caller read, because these race each other across two Kafka
     * topics and two listener threads. Transcoding takes minutes, so a moderator's takedown
     * routinely lands while a transcode result is in flight; an unconditional write lets whichever
     * arrives last win, which puts a taken-down video back on the feed with nothing left to say it
     * was ever removed.
     *
     * @param expectedStatus the status read before the change was applied
     * @return false when the status moved underneath — re-read and re-apply, do not retry the
     *         same write
     */
    boolean updateStatus(Video video, VideoStatus expectedStatus);

    /**
     * A failed transcode: the status pair plus the reason it failed. Separate from
     * {@link #updateStatus} (takedown/restore) because only this path owns {@code failureReason} —
     * keeping the write field-scoped to what the operation owns, like every other method here.
     *
     * <p>Conditioned on the status the caller read, same as {@link #updateStatus}: a takedown can
     * land while the transcode is still running.
     *
     * @return false when the status moved underneath — re-read and re-apply
     */
    boolean updateFailed(Video video, VideoStatus expectedStatus);

    /**
     * The automatic moderation verdict: the status pair plus the scores behind it.
     *
     * <p>Separate from {@link #updateStatus} because only this path owns {@code moderation},
     * keeping each write scoped to the fields its own operation owns.
     *
     * <p>Conditioned on the status the caller read, for the reason {@link #updateStatus} gives and
     * one specific to this path: a moderator can take a video down while the classifier is still
     * scoring it, and an unconditional APPROVED landing afterwards would put it back on the feed.
     *
     * @return false when the status moved underneath — re-read and re-apply
     */
    boolean updateModeration(Video video, VideoStatus expectedStatus);

    /** Outbox flag, set once the broker acknowledges the VideoPublishedEvent. */
    void updateEventPublished(Video video);

    /** Outbox flag, set once the broker acknowledges the VideoDeletedEvent. */
    void updateDeleteEventPublished(Video video);

    /** Outbox flag, set once the broker acknowledges the VideoPurgedEvent. */
    void updatePurgeEventPublished(Video video);

    /**
     * Purge outbox poll: deleted videos whose trash window has run out and whose VideoPurgedEvent
     * has not gone out yet. Not a derived query — {@code deletedAt} needs both {@code ne(null)}
     * and {@code lt(purgeBefore)} in the same clause, which two derived-query keywords on one
     * field cannot express (Spring Data rejects the second as a duplicate key).
     *
     * <p>{@code purgeBefore} is "now minus the retention period" — see
     * {@code VideoEventPublisher#publishPendingPurges} — so a video stays out of this poll, and
     * its media stays in MinIO, for as long as it sits in the trash. That is what lets an admin
     * still watch a video its owner deleted: the event that makes media-worker erase it does not
     * go out until this query starts returning the row. The deletion itself is announced right
     * away by {@link VideoRepository#findTop100ByDeletedAtIsNotNullAndDeleteEventPublishedAtIsNullOrderByDeletedAtAsc}.
     */
    List<Video> findPendingPurge(Instant purgeBefore, int limit);

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

    /**
     * Uploads per day since the cutoff, with the current standing of that day's cohort — what
     * the admin console's growth percentages are computed from.
     *
     * <p>Aggregated on read rather than kept as a rollup collection: the console asks for this
     * once per page view over a bounded window, and a second copy of a count is a second thing
     * that can drift from the videos themselves.
     *
     * <p>Owner-deleted videos are counted, the same as {@link #findForAdmin} with no
     * {@code deleted} filter, so the percentage under a total describes the same set of videos
     * the total counted. It is also the only answer that holds still: an upload happened on the
     * day it happened, and excluding deletions would quietly walk a past day's figure downwards
     * every time an owner cleared out an old video.
     */
    List<DailyVideoStatsResponse> countDailyUploads(Instant since);

    /** Soft delete. */
    void updateSoftDeleted(Video video);

    /**
     * The owner toggling PUBLIC/PRIVATE from the detail page. Unconditional like
     * {@link #updateSoftDeleted}: visibility is owner-driven and not contended by the Kafka
     * consumers the {@code compareAndSet} writes guard against.
     */
    void updateVisibility(Video video);

    /**
     * Clears the visibility outbox slot, but only if it still holds the moment that was actually
     * announced. An owner who changes visibility again while the broker is acknowledging the
     * previous change writes a newer timestamp into the slot; clearing it unconditionally would
     * throw that change away and leave every read path holding the value before it.
     *
     * @return false when the slot moved underneath, meaning a newer change is queued and the next
     *         poll will send it
     */
    boolean clearVisibilityEventPending(String videoId, java.time.Instant announced);

    /**
     * The owner turning comments on/off from the detail page. Unconditional, same reasoning as
     * {@link #updateVisibility}: owner-driven and not contended by the Kafka consumers.
     */
    void updateCommentsDisabled(Video video);
}
