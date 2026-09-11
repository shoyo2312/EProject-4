package com.tiktok.interactionservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.CommentLikeResponse;
import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentByVideo;
import com.tiktok.interactionservice.entity.CommentByVideoKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.CommentNotFoundException;
import com.tiktok.interactionservice.exception.CommentsDisabledException;
import com.tiktok.interactionservice.exception.CommentRateLimitedException;
import com.tiktok.interactionservice.exception.InvalidCommentCursorException;
import com.tiktok.interactionservice.exception.NotCommentOwnerException;
import com.tiktok.interactionservice.mapper.CommentMapper;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentLikeRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** How many all-deleted pages to skip past before handing the client a cursor and giving up. */
    private static final int MAX_PAGES_SCANNED = 5;

    /** How many times a losing like-tally write re-reads and tries again — see moveLikes. */
    private static final int MAX_LIKE_CAS_RETRIES = 3;

    private final CommentByVideoRepository commentByVideoRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final CommentMapper commentMapper;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionRateLimiter rateLimiter;
    private final VideoOwnershipClient videoOwnershipClient;

    @Override
    public CommentResponse addComment(Long videoId, Long currentUserId, String content, Long parentId) {
        // Same reasoning as ShareServiceImpl: nothing about a comment is idempotent, so the row,
        // the counter and the +2 it puts into trending all repeat for as long as a client keeps
        // calling. A like is protected by its LWT and a view by its playId; this endpoint has
        // neither, and posting then deleting in a loop moves the ranking either way.
        rateLimiter.require("comment-rate", videoId, currentUserId, CommentRateLimitedException::new);

        // The owner's comments-off switch lives on the Video in video-service; this is the only
        // place that enforces it. Fails open (see areCommentsDisabled) — an unreachable
        // video-service lets the comment through rather than blocking every comment site-wide.
        if (videoOwnershipClient.areCommentsDisabled(videoId)) {
            throw new CommentsDisabledException(videoId);
        }

        // A reply points at its top-level comment. Replying to a reply flattens onto that reply's
        // own parent, so the thread is always one level deep (as on TikTok) and no reply is ever
        // orphaned under a parent the client will not render. A parentId that names nothing on this
        // video is the client sending a stale or wrong id — refused rather than stored dangling.
        Long resolvedParentId = null;
        Long replyToUserId = null;
        if (parentId != null) {
            CommentByVideo parent = commentByVideoRepository
                    .findById(CommentByVideoKey.builder().videoId(videoId).commentId(parentId).build())
                    .filter(c -> !c.isDeleted())
                    .orElseThrow(() -> new CommentNotFoundException(parentId));
            resolvedParentId = parent.getParentId() != null ? parent.getParentId() : parentId;
            // Target was itself a reply => record its author so the flat list shows "A > B". A direct
            // reply to a top-level comment leaves this null: its target is the thread owner above it.
            replyToUserId = parent.getParentId() != null ? parent.getUserId() : null;
        }

        Long commentId = SnowflakeIdGenerator.nextId();
        CommentByVideoKey key = CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build();

        CommentByVideo comment = CommentByVideo.builder()
                .key(key)
                .userId(currentUserId)
                .content(content)
                .parentId(resolvedParentId)
                .replyToUserId(replyToUserId)
                .createdAt(Instant.now())
                .build();
        commentByVideoRepository.save(comment);

        // A stored comment whose counter never moved leaves the video showing fewer comments than
        // it lists, permanently: a counter table has no recount, and nothing downstream reconciles
        // the two. Removed rather than soft-deleted — this row was never visible to anyone, so a
        // tombstone would only be a deleted comment for the listing to page past.
        boolean countered = false;
        try {
            videoCountersRepository.incrementCommentCount(videoId, 1);
            countered = true;
            counterCacheService.invalidate(videoId);
            eventPublisher.publishCommentCreated(commentId, videoId, currentUserId, content,
                    resolvedParentId, replyToUserId);
        } catch (RuntimeException ex) {
            // The counter is taken back as well as the row. Removing the row while the increment
            // stands leaves the video showing more comments than it lists — the mirror image of
            // the divergence this compensation exists to prevent — and the client's retry adds
            // another one on top.
            if (countered) {
                undoCounter(videoId, -1);
            }
            commentByVideoRepository.deleteById(key);
            throw ex;
        }

        return commentMapper.toResponse(comment);
    }

    /**
     * Deleted comments are filtered after Cassandra has already cut the page, so a page whose rows
     * were all deleted comes back empty while hasMore says otherwise — a client that stops on an
     * empty page stops early, and one that does not has to loop itself. Skipping such pages here
     * keeps that loop in one place.
     *
     * <p>ponytail: bounded to a few pages, so a video with thousands of consecutive deleted
     * comments can still return an empty page rather than scan forever. Filtering in the query is
     * the real fix, and Cassandra cannot do it without a secondary index nobody wants.
     */
    @Override
    public CommentPageResponse listComments(Long videoId, String cursor, int size, Long currentUserId) {
        // Comments off => the owner hid the thread, not just new replies: no rows, no cursor.
        // ponytail: one video-service call per comment-list load. The client already has the
        // flag on the Video and skips this call; a raw API caller is the only one that pays it.
        if (videoOwnershipClient.areCommentsDisabled(videoId)) {
            return new CommentPageResponse(List.of(), null, false);
        }

        CassandraPageRequest pageRequest = CassandraCursors.decode(cursor, size, InvalidCommentCursorException::new);

        List<CommentResponse> items = List.of();
        Slice<CommentByVideo> slice;

        for (int page = 0; page < MAX_PAGES_SCANNED; page++) {
            // Base64 that decodes into bytes Cassandra will not accept as paging state is only
            // refused here, by the coordinator, not by the decoder — and only the first page can
            // be carrying the client's value, every page after it uses one this method issued.
            try {
                slice = commentByVideoRepository.findByVideoId(videoId, pageRequest);
            } catch (CassandraInvalidQueryException e) {
                if (page == 0 && cursor != null && !cursor.isBlank()) {
                    throw new InvalidCommentCursorException();
                }
                throw e;
            }

            List<CommentByVideo> live = slice.getContent().stream()
                    .filter(comment -> !comment.isDeleted())
                    .toList();
            items = toResponses(live, currentUserId);

            boolean hasMore = slice.hasNext();
            String nextCursor = hasMore ? CassandraCursors.encode((CassandraPageRequest) slice.nextPageable()) : null;

            if (!items.isEmpty() || !hasMore) {
                return new CommentPageResponse(items, nextCursor, hasMore);
            }
            pageRequest = (CassandraPageRequest) slice.nextPageable();
        }

        return new CommentPageResponse(items, CassandraCursors.encode(pageRequest), true);
    }

    @Override
    public void deleteComment(Long videoId, Long commentId, Long currentUserId) {
        CommentByVideoKey key = CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build();
        CommentByVideo comment = commentByVideoRepository.findById(key)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new CommentNotFoundException(commentId));

        // Deleting your own comment never needs the extra lookup — only a caller reaching for
        // somebody else's comment falls through to asking video-service whether they own the video
        // it is on.
        boolean allowed = comment.getUserId().equals(currentUserId)
                || videoOwnershipClient.isOwnedBy(videoId, currentUserId);
        if (!allowed) {
            throw new NotCommentOwnerException(commentId);
        }

        if (!softDelete(videoId, commentId, currentUserId)) {
            throw new CommentNotFoundException(commentId);
        }
    }

    @Override
    public void removeByAdmin(Long videoId, Long commentId) {
        CommentByVideo comment = commentByVideoRepository
                .findById(CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build())
                .orElse(null);

        // Unknown or already gone is a no-op, not an error: this runs off a Kafka event, and a
        // moderation event naming a comment this service has never seen — or one a redelivery
        // already applied — must not be retried into the DLQ. The consumer contract in CLAUDE.md
        // requires ignoring unknown ids for exactly this reason.
        if (comment == null || comment.isDeleted()) {
            log.debug("Moderation removal of comment {} on video {}: nothing to remove", commentId, videoId);
            return;
        }

        // The comment's own author, not the admin: this event feeds video-service's counter and
        // recommendation-service's tag profiles, which read the id as a participant in the video's
        // interactions. An admin id there would attribute the removal to somebody who never
        // interacted with the video at all.
        softDelete(videoId, commentId, comment.getUserId());
    }

    /**
     * Soft-deletes one comment and takes its counter down with it. Returns whether this call is
     * the one that deleted it.
     *
     * <p>The caller's read answers "may this caller delete it"; it cannot answer "is it still
     * there", because another delete can land between the read and the write. The condition does,
     * and only the caller it applies for is allowed to move the counter — a save() would let both
     * racing deletes decrement, and a counter table has no way back from that.
     *
     * <p>The deletion is undone if the counter never moved, for the reason addComment restores its
     * row: this caller is the only one allowed to decrement for this comment, so a failure here is
     * the one and only chance to apply it. Conditioned on the deletion still being ours, so a
     * restore cannot resurrect a comment somebody deleted in the meantime.
     */
    private boolean softDelete(Long videoId, Long commentId, Long userId) {
        Instant deletedAt = Instant.now();
        if (!commentByVideoRepository.markDeletedIfNotDeleted(videoId, commentId, deletedAt)) {
            return false;
        }

        boolean countered = false;
        try {
            videoCountersRepository.incrementCommentCount(videoId, -1);
            countered = true;
            counterCacheService.invalidate(videoId);
            eventPublisher.publishCommentDeleted(commentId, videoId, userId);
        } catch (RuntimeException ex) {
            if (countered) {
                undoCounter(videoId, 1);
            }
            commentByVideoRepository.restoreIfDeletedAt(videoId, commentId, deletedAt);
            throw ex;
        }
        return true;
    }

    @Override
    public CommentLikeResponse likeComment(Long videoId, Long commentId, Long currentUserId) {
        CommentByVideo comment = liveComment(videoId, commentId);

        // The membership LWT is what grants the right to move the count, and it grants it once:
        // a retried like finds the row already there, is told newlyLiked=false, and leaves the
        // tally alone.
        boolean newlyLiked = commentLikeRepository.insertIfNotExists(commentId, currentUserId, Instant.now());
        int likeCount = newlyLiked ? moveLikes(videoId, commentId, comment, 1) : comment.likeCount();
        // Only on an actual change: a retried like moved nothing, and announcing it would wake
        // every open panel to redraw the number it already shows.
        if (newlyLiked) {
            eventPublisher.publishCommentLikeChanged(commentId, videoId, currentUserId, true, likeCount);
        }
        return new CommentLikeResponse(commentId, true, likeCount);
    }

    @Override
    public CommentLikeResponse unlikeComment(Long videoId, Long commentId, Long currentUserId) {
        CommentByVideo comment = liveComment(videoId, commentId);

        boolean wasLiked = commentLikeRepository.deleteIfExists(commentId, currentUserId);
        int likeCount = wasLiked ? moveLikes(videoId, commentId, comment, -1) : comment.likeCount();
        if (wasLiked) {
            eventPublisher.publishCommentLikeChanged(commentId, videoId, currentUserId, false, likeCount);
        }
        return new CommentLikeResponse(commentId, false, likeCount);
    }

    /**
     * Moves the denormalised tally by one, conditioned on the value that was read — the membership
     * LWT above says this caller may move the count, not that nobody else is moving it at the same
     * moment. A losing write re-reads and tries again rather than overwriting whoever won.
     *
     * <p>Giving up after the retries leaves the tally one short rather than failing the request:
     * the like itself is already recorded, and an error here would tell the caller their like did
     * not happen when it did.
     */
    private int moveLikes(Long videoId, Long commentId, CommentByVideo read, int delta) {
        CommentByVideo current = read;
        for (int attempt = 0; attempt <= MAX_LIKE_CAS_RETRIES; attempt++) {
            int next = Math.max(0, current.likeCount() + delta);
            boolean applied = current.getLikes() == null
                    ? commentByVideoRepository.initLikesIfUnset(videoId, commentId, next)
                    : commentByVideoRepository.updateLikesIfMatches(
                            videoId, commentId, next, current.getLikes());
            if (applied) {
                return next;
            }
            current = liveComment(videoId, commentId);
        }
        log.warn("Like tally on comment {} of video {} lost a change after {} attempts; it is now "
                + "off by one", commentId, videoId, MAX_LIKE_CAS_RETRIES + 1);
        return current.likeCount();
    }

    private CommentByVideo liveComment(Long videoId, Long commentId) {
        return commentByVideoRepository
                .findById(CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build())
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new CommentNotFoundException(commentId));
    }

    /**
     * Maps a page of live comments and, for a signed-in caller, marks the ones they have liked in
     * a single {@code comment_likes} lookup. Anonymous callers skip the lookup and get
     * {@code likedByMe} false throughout.
     */
    private List<CommentResponse> toResponses(List<CommentByVideo> live, Long currentUserId) {
        List<CommentResponse> mapped = live.stream().map(commentMapper::toResponse).toList();
        if (currentUserId == null || mapped.isEmpty()) {
            return mapped;
        }
        List<Long> ids = mapped.stream().map(CommentResponse::commentId).toList();
        Set<Long> liked = new HashSet<>(commentLikeRepository.findLikedCommentIds(ids, currentUserId));
        return mapped.stream()
                .map(response -> liked.contains(response.commentId()) ? response.withLikedByMe(true) : response)
                .toList();
    }

    /**
     * Takes back an increment whose request did not finish. Swallowed on purpose: an exception is
     * already on its way to the caller and it is the one worth seeing, and a compensation that
     * fails leaves exactly the inconsistency that existed without it — logged, and no worse.
     */
    private void undoCounter(Long videoId, long delta) {
        try {
            videoCountersRepository.incrementCommentCount(videoId, delta);
        } catch (RuntimeException e) {
            log.error("Could not take back the comment count change on video {}; it is now off by one",
                    videoId, e);
        }
    }
}
