package com.tiktok.interactionservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.interactionservice.client.BlockClient;
import com.tiktok.interactionservice.client.CommentAudience;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.FriendshipClient;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.CommentLikeResponse;
import com.tiktok.interactionservice.dto.response.CommentPermissionResponse;
import com.tiktok.interactionservice.dto.response.CommentPermissionResponse.Restriction;
import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentByVideo;
import com.tiktok.interactionservice.entity.CommentByVideoKey;
import com.tiktok.interactionservice.entity.CommentCounters;
import com.tiktok.interactionservice.entity.CommentIndex;
import com.tiktok.interactionservice.entity.CommentLikeKey;
import com.tiktok.interactionservice.entity.CommentStatus;
import com.tiktok.interactionservice.entity.HeldComment;
import com.tiktok.interactionservice.entity.HeldCommentKey;
import com.tiktok.interactionservice.event.producer.InteractionEventPublisher;
import com.tiktok.interactionservice.exception.CommentNotFoundException;
import com.tiktok.interactionservice.exception.CommentsDisabledException;
import com.tiktok.interactionservice.exception.CommentsNotAllowedException;
import com.tiktok.interactionservice.exception.CommentLikeRateLimitedException;
import com.tiktok.interactionservice.exception.CommentRejectedException;
import com.tiktok.interactionservice.exception.InvalidCommentCursorException;
import com.tiktok.interactionservice.exception.NotCommentOwnerException;
import com.tiktok.interactionservice.exception.NotVideoOwnerException;
import com.tiktok.interactionservice.exception.ReplyNotAllowedException;
import com.tiktok.interactionservice.mapper.CommentMapper;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentCountersRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import com.tiktok.interactionservice.repository.CommentLikeRepository;
import com.tiktok.interactionservice.repository.HeldCommentRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.TextVerdict;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommentServiceImpl implements CommentService {

    /** How many all-deleted pages to skip past before handing the client a cursor and giving up. */
    private static final int MAX_PAGES_SCANNED = 5;

    /** How many replies one cascade delete takes off the index at a time. */
    private static final int CASCADE_PAGE_SIZE = 100;

    /** How many times a losing like-tally write re-reads and tries again — see moveLikes. */
    private static final int MAX_LIKE_CAS_RETRIES = 3;

    private final CommentByVideoRepository commentByVideoRepository;
    private final CommentIndexRepository commentIndexRepository;
    private final CommentCountersRepository commentCountersRepository;
    private final CommentLikeRepository commentLikeRepository;
    private final VideoCountersRepository videoCountersRepository;
    private final CounterCacheService counterCacheService;
    private final CommentMapper commentMapper;
    private final HeldCommentRepository heldCommentRepository;
    private final InteractionEventPublisher eventPublisher;
    private final InteractionRateLimiter rateLimiter;
    private final CommentRateLimiter commentRateLimiter;
    private final DuplicateCommentGuard duplicateCommentGuard;
    private final CommentScreening commentScreening;
    private final CommentStrikes commentStrikes;
    private final UserBanRegistry userBanRegistry;
    private final VideoOwnershipClient videoOwnershipClient;
    private final FriendshipClient friendshipClient;
    private final BlockClient blockClient;

    @Override
    public CommentResponse addComment(Long videoId, Long currentUserId, String content, Long parentId) {
        // First of all: an account restricted for its strikes is refused before it spends rate-limit
        // budget or costs anyone a round trip, and is told when the restriction ends.
        commentStrikes.requireNotRestricted(currentUserId);
        // Ahead of both video-service calls, so a bot is refused in Redis without costing a round
        // trip; and ahead of the text rules, so a comment they refuse still spends budget and the
        // blocklist cannot be probed at speed. Nothing about a comment is idempotent — the row, the
        // counter and the +2 it puts into trending all repeat for as long as a client keeps calling.
        // A like is protected by its LWT and a view by its playId; this endpoint has neither.
        commentRateLimiter.require(currentUserId, videoId);
        videoOwnershipClient.requireVisible(videoId);

        // The per-video switch and the owner's account-level audience, from one /policy read.
        // Fails open (see commentPolicy) — an unreachable video-service lets the comment through
        // rather than blocking every comment site-wide. Note this is the opposite of chat's block
        // and DM checks, which fail closed; the asymmetry is deliberate on both sides.
        CommentPolicy policy = videoOwnershipClient.commentPolicy(videoId);
        Restriction restriction = restrictionFor(policy, currentUserId);
        if (restriction == Restriction.COMMENTS_DISABLED) {
            throw new CommentsDisabledException(videoId);
        }
        if (restriction != null) {
            throw new CommentsNotAllowedException(videoId);
        }

        // A reply points at its top-level comment. Replying to a reply flattens onto that reply's
        // own parent, so the thread is always one level deep (as on TikTok) and no reply is ever
        // orphaned under a parent the client will not render. A parentId that names nothing live on
        // this video — unknown, deleted, or still held for review — is refused rather than stored
        // dangling: a reply to a held comment would be readable while the comment it answers is not.
        Long resolvedParentId = null;
        Long replyToUserId = null;
        Long parentAuthorId = null;
        if (parentId != null) {
            CommentByVideo parent = commentByVideoRepository
                    .findById(CommentByVideoKey.builder().videoId(videoId).commentId(parentId).build())
                    .filter(CommentByVideo::isLive)
                    .orElseThrow(() -> new CommentNotFoundException(parentId));
            // A reply lands in front of the person it answers — in their thread and their inbox —
            // which is exactly the contact a block between the two of them exists to stop.
            if (blockClient.isBlockedBetween(parent.getUserId(), currentUserId)) {
                throw new ReplyNotAllowedException(parentId);
            }
            resolvedParentId = parent.getParentId() != null ? parent.getParentId() : parentId;
            // Target was itself a reply => record its author so the flat list shows "A > B". A direct
            // reply to a top-level comment leaves this null: its target is the thread owner above it.
            replyToUserId = parent.getParentId() != null ? parent.getUserId() : null;
            parentAuthorId = parent.getUserId();
        }

        // The rules, the owner's own filters, then the classifier — see CommentScreening. The owner
        // reviews held comments, so theirs is never held: they would only be approving themselves.
        // Refused like anyone's, though — severity 3 is abuse whoever writes it — and a refusal is a
        // strike against the author.
        CommentScreening.Outcome outcome = commentScreening.screen(content, currentUserId, policy);
        if (outcome.decision() == Decision.REJECT) {
            commentStrikes.strike(currentUserId, "comment refused, "
                    + outcome.verdict().worst().map(Flag::toString).orElse("no flag"));
            throw new CommentRejectedException();
        }
        boolean held = outcome.decision() == Decision.HOLD;
        TextVerdict verdict = outcome.verdict();

        // After the rules: a refused comment sent again is refused again for what it says, not
        // called a duplicate. Released if nothing gets stored, so a retry after a failure here is
        // not refused as a copy of itself. Not asked of the owner on their own video: thanking each
        // fan with the same "cảm ơn bạn" is how creators answer, and there it is spam to no one.
        boolean owner = currentUserId.equals(policy.ownerId());
        DuplicateCommentGuard.Claim claim = owner
                ? DuplicateCommentGuard.Claim.NONE
                : duplicateCommentGuard.claim(currentUserId, videoId, parentId, content);
        try {
            return held
                    ? storeHeld(videoId, currentUserId, content, resolvedParentId, replyToUserId, verdict)
                    : storeVisible(videoId, currentUserId, content, resolvedParentId, replyToUserId, parentAuthorId,
                            verdict);
        } catch (RuntimeException ex) {
            claim.release();
            throw ex;
        }
    }

    /**
     * Posted for everyone. Its flags are kept when there are any — rules not enforced, the owner's own
     * comment, an owner who waived the category, a classifier note — so the admin list shows what was
     * let through and why. That is how a filter's misses and the classifier's precision get seen.
     */
    private CommentResponse storeVisible(Long videoId, Long currentUserId, String content, Long resolvedParentId,
                                         Long replyToUserId, Long parentAuthorId, TextVerdict verdict) {
        Long commentId = SnowflakeIdGenerator.nextId();
        CommentByVideoKey key = CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build();

        boolean flagged = !verdict.flags().isEmpty();
        CommentByVideo comment = CommentByVideo.builder()
                .key(key)
                .userId(currentUserId)
                .content(content)
                .parentId(resolvedParentId)
                .replyToUserId(replyToUserId)
                .status(CommentStatus.VISIBLE)
                .modReason(flagged ? verdict.summary() : null)
                .modVersion(flagged ? verdict.version() : null)
                .createdAt(Instant.now())
                .build();
        commentByVideoRepository.save(comment);

        // A stored comment whose counter never moved leaves the video showing fewer comments than
        // it lists, permanently: a counter table has no recount, and nothing downstream reconciles
        // the two. Removed rather than soft-deleted — this row was never visible to anyone, so a
        // tombstone would only be a deleted comment for the listing to page past.
        try {
            announce(comment, parentAuthorId);
        } catch (RuntimeException ex) {
            commentByVideoRepository.deleteById(key);
            throw ex;
        }

        return commentMapper.toResponse(comment);
    }

    /**
     * Stored for the owner to review, and nothing else: no index row (the listings read the index,
     * so it cannot leak into them), no counter, no event. Its queue row is written with it; a held
     * comment the owner's queue cannot find would wait for ever, so if that write fails the comment
     * goes too.
     */
    private CommentResponse storeHeld(Long videoId, Long currentUserId, String content, Long resolvedParentId,
                                      Long replyToUserId, TextVerdict verdict) {
        Long commentId = SnowflakeIdGenerator.nextId();
        CommentByVideoKey key = CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build();

        CommentByVideo comment = CommentByVideo.builder()
                .key(key)
                .userId(currentUserId)
                .content(content)
                .parentId(resolvedParentId)
                .replyToUserId(replyToUserId)
                .status(CommentStatus.HELD)
                .modReason(verdict.summary())
                .modVersion(verdict.version())
                .createdAt(Instant.now())
                .build();
        commentByVideoRepository.save(comment);
        try {
            heldCommentRepository.save(HeldComment.of(videoId, commentId));
        } catch (RuntimeException ex) {
            commentByVideoRepository.deleteById(key);
            throw ex;
        }
        return commentMapper.toResponse(comment);
    }

    /**
     * What makes a stored comment exist for everyone else: the counters, its index row, the event.
     * A new comment takes all three here; an approved one is counted before it turns VISIBLE — see
     * approveComment — and takes the other two after. Any failure takes back what already landed —
     * the counter as well as the index row, because a row removed while its increment stands leaves
     * the video showing more comments than it lists, and the client's retry adds another on top — and
     * rethrows for the caller to undo its own write.
     */
    private void announce(CommentByVideo comment, Long parentAuthorId) {
        count(comment);
        try {
            listAndPublish(comment, parentAuthorId);
        } catch (RuntimeException ex) {
            uncount(comment);
            throw ex;
        }
    }

    /** Puts a comment on its video's count, and a reply on its thread's; on a failure, neither. */
    private void count(CommentByVideo comment) {
        Long videoId = comment.getKey().getVideoId();
        Long parentId = comment.getParentId();

        boolean replyCounted = false;
        try {
            if (parentId != null) {
                commentCountersRepository.incrementReplyCount(videoId, parentId, 1);
                replyCounted = true;
            }
            videoCountersRepository.incrementCommentCount(videoId, 1);
        } catch (RuntimeException ex) {
            if (replyCounted) {
                undoReplyCounter(videoId, parentId, -1);
            }
            throw ex;
        }
        counterCacheService.invalidate(videoId);
    }

    /** Takes back a {@link #count}, for a comment that is not going to be shown after all. */
    private void uncount(CommentByVideo comment) {
        Long videoId = comment.getKey().getVideoId();
        undoCounter(videoId, -1);
        if (comment.getParentId() != null) {
            undoReplyCounter(videoId, comment.getParentId(), -1);
        }
        counterCacheService.invalidate(videoId);
    }

    /**
     * Lists a counted comment and tells everyone it exists. The listing reads the index, not
     * comments_by_video: a comment with no index row is stored but invisible, so the row is written
     * before anything announces the comment — and goes again if the announcement fails.
     */
    private void listAndPublish(CommentByVideo comment, Long parentAuthorId) {
        Long videoId = comment.getKey().getVideoId();
        Long commentId = comment.getKey().getCommentId();
        Long parentId = comment.getParentId();

        commentIndexRepository.save(CommentIndex.of(videoId, parentId, commentId));
        try {
            eventPublisher.publishCommentCreated(commentId, videoId, comment.getUserId(), comment.getContent(),
                    parentId, comment.getReplyToUserId(), parentAuthorId);
        } catch (RuntimeException ex) {
            commentIndexRepository.delete(CommentIndex.of(videoId, parentId, commentId));
            throw ex;
        }
    }

    /**
     * The video's own reasons first — comments off, the owner's audience — then the caller's own: an
     * account restricted for its strikes, with when that ends.
     */
    @Override
    public CommentPermissionResponse commentPermission(Long videoId, Long currentUserId) {
        videoOwnershipClient.requireVisible(videoId);
        Restriction restriction = restrictionFor(videoOwnershipClient.commentPolicy(videoId), currentUserId);
        if (restriction != null) {
            return new CommentPermissionResponse(restriction, null);
        }
        return commentStrikes.restriction(currentUserId)
                .map(remaining -> new CommentPermissionResponse(Restriction.COMMENTING_RESTRICTED,
                        Instant.now().plus(remaining).truncatedTo(ChronoUnit.SECONDS)))
                .orElseGet(() -> new CommentPermissionResponse(null, null));
    }

    /**
     * Why {@code commenterId} may not comment, or null when they may — the one rule both
     * {@link #addComment} and {@link #commentPermission} read, so the comment box a client hides
     * and the 403 it would otherwise meet can never disagree.
     *
     * <p>The per-video switch and the owner's account-level audience are both restrictions, never
     * an override in either direction: a video with comments off stays off however permissive the
     * account is, and an account set to NO_ONE closes videos whose own switch was never touched.
     * Both bind the owner too — NO_ONE reads as comments off on every video the account owns, the
     * same as the switch, and is shown that way. FRIENDS is the one rule the owner is exempt from:
     * nobody is their own friend, and it is still their thread.
     *
     * <p>An unknown owner (video-service unreachable) passes: at that point there is no audience
     * to enforce, and this guard fails open by design.
     */
    private Restriction restrictionFor(CommentPolicy policy, Long commenterId) {
        if (policy.commentsDisabled()) {
            return Restriction.COMMENTS_DISABLED;
        }
        if (policy.commentAudience() == CommentAudience.NO_ONE) {
            return Restriction.NO_ONE;
        }
        if (policy.ownerId() == null || policy.ownerId().equals(commenterId)) {
            return null;
        }

        return switch (policy.commentAudience()) {
            case NO_ONE -> Restriction.NO_ONE;
            // Fails closed, unlike the audience lookup: "only my friends" must not become
            // "everyone" because user-service could not be reached. See FriendshipClient.
            case FRIENDS -> friendshipClient.areFriends(policy.ownerId(), commenterId)
                    ? null
                    : Restriction.FRIENDS_ONLY;
            case EVERYONE -> null;
        };
    }

    @Override
    public CommentPageResponse listComments(Long videoId, String cursor, int size, Long currentUserId) {
        videoOwnershipClient.requireVisible(videoId);
        // Comments off => the owner hid the thread, not just new replies: no rows, no cursor.
        // ponytail: one video-service call per comment-list load. The client already has the
        // flag on the Video and skips this call; a raw API caller is the only one that pays it.
        if (videoOwnershipClient.areCommentsDisabled(videoId)) {
            return new CommentPageResponse(List.of(), null, false);
        }
        return page(videoId, CommentIndex.TOP_LEVEL, cursor, size, currentUserId);
    }

    @Override
    public CommentPageResponse listReplies(Long videoId, Long parentId, String cursor, int size,
                                           Long currentUserId) {
        videoOwnershipClient.requireVisible(videoId);
        if (videoOwnershipClient.areCommentsDisabled(videoId)) {
            return new CommentPageResponse(List.of(), null, false);
        }
        // No existence check on the parent: an unknown id is an empty partition, which is the same
        // empty page a parent with no replies gives, and one fewer read on the common path.
        return page(videoId, parentId, cursor, size, currentUserId);
    }

    /**
     * One page of a single thread. The top-level thread reads newest first, a reply thread oldest
     * first, and both are one Cassandra partition in {@code comment_index} — the ids come from
     * there and the comments themselves from one {@code IN} on {@code comments_by_video}.
     *
     * <p>Deleted comments are filtered after Cassandra has already cut the page, so a page whose
     * rows were all deleted comes back empty while hasMore says otherwise — a client that stops on
     * an empty page stops early, and one that does not has to loop itself. Skipping such pages
     * here keeps that loop in one place.
     *
     * <p>ponytail: bounded to a few pages, so a thread with thousands of consecutive deleted
     * comments can still return an empty page rather than scan forever. Filtering in the query is
     * the real fix, and Cassandra cannot do it without a secondary index nobody wants.
     */
    private CommentPageResponse page(Long videoId, long threadId, String cursor, int size, Long currentUserId) {
        boolean topLevel = threadId == CommentIndex.TOP_LEVEL;
        return scan(cursor, size,
                pageRequest -> (topLevel
                        ? commentIndexRepository.findNewestFirst(videoId, threadId, pageRequest)
                        : commentIndexRepository.findOldestFirst(videoId, threadId, pageRequest))
                        .map(row -> row.getKey().getCommentId()),
                ids -> hydrate(videoId, ids, currentUserId, topLevel));
    }

    /**
     * Walks pages of ids until one hydrates into something, so a run of filtered-out comments does
     * not hand the client an empty page it might stop on — see {@link #page}. Shared by the public
     * threads and the owner's queue of held comments.
     */
    private CommentPageResponse scan(String cursor, int size,
                                     Function<CassandraPageRequest, Slice<Long>> ids,
                                     Function<List<Long>, List<CommentResponse>> hydrate) {
        CassandraPageRequest pageRequest = CassandraCursors.decode(cursor, size, InvalidCommentCursorException::new);

        List<CommentResponse> items = List.of();

        for (int page = 0; page < MAX_PAGES_SCANNED; page++) {
            // Base64 that decodes into bytes Cassandra will not accept as paging state is only
            // refused here, by the coordinator, not by the decoder — and only the first page can
            // be carrying the client's value, every page after it uses one this method issued.
            Slice<Long> slice;
            try {
                slice = ids.apply(pageRequest);
            } catch (CassandraInvalidQueryException e) {
                if (page == 0 && cursor != null && !cursor.isBlank()) {
                    throw new InvalidCommentCursorException();
                }
                throw e;
            }

            items = hydrate.apply(slice.getContent());

            boolean hasMore = slice.hasNext();
            String nextCursor = hasMore ? CassandraCursors.encode((CassandraPageRequest) slice.nextPageable()) : null;

            if (!items.isEmpty() || !hasMore) {
                return new CommentPageResponse(items, nextCursor, hasMore);
            }
            pageRequest = (CassandraPageRequest) slice.nextPageable();
        }

        return new CommentPageResponse(items, CassandraCursors.encode(pageRequest), true);
    }

    /**
     * Turns a page of ids into responses, dropping the ones whose comment is soft-deleted — or held,
     * which the index never lists, filtered here as well so a stray index row cannot publish one —
     * and the ones whose author is banned right now. The {@code IN} comes back in the table's own
     * order, so the ids are walked rather than the rows — the page keeps the order the index gave it.
     */
    private List<CommentResponse> hydrate(Long videoId, List<Long> ids, Long currentUserId,
                                          boolean withReplyCounts) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, CommentByVideo> rows = withoutBannedAuthors(
                commentByVideoRepository.findAllByCommentIds(videoId, ids).stream()
                        .filter(CommentByVideo::isLive)
                        .collect(Collectors.toMap(comment -> comment.getKey().getCommentId(), Function.identity())));

        List<CommentResponse> mapped = toResponses(
                ids.stream().map(rows::get).filter(Objects::nonNull).toList(), currentUserId);
        return withReplyCounts ? attachReplyCounts(videoId, mapped) : mapped;
    }

    /**
     * The page less every comment whose author is banned as of now — one read for the page's authors.
     * Counters are left alone (see {@link UserBanRegistry}), so the totals a video shows still include
     * them; a lapsed or lifted ban brings the comments back with nothing to recount.
     */
    private Map<Long, CommentByVideo> withoutBannedAuthors(Map<Long, CommentByVideo> rows) {
        if (rows.isEmpty()) {
            return rows;
        }
        Set<Long> banned = userBanRegistry.bannedAmong(
                rows.values().stream().map(CommentByVideo::getUserId).collect(Collectors.toSet()));
        if (banned.isEmpty()) {
            return rows;
        }
        return rows.entrySet().stream()
                .filter(entry -> !banned.contains(entry.getValue().getUserId()))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * Fills in "View N replies" for a top-level page in one counter read. Replies skip it: the
     * thread is one level deep, so a reply never has replies of its own and its count stays 0.
     */
    private List<CommentResponse> attachReplyCounts(Long videoId, List<CommentResponse> items) {
        if (items.isEmpty()) {
            return items;
        }
        Map<Long, Integer> counts = commentCountersRepository
                .findAllByCommentIds(videoId, items.stream().map(CommentResponse::commentId).toList())
                .stream()
                .collect(Collectors.toMap(row -> row.getKey().getCommentId(), CommentCounters::replies));
        return items.stream()
                .map(item -> item.withReplyCount(counts.getOrDefault(item.commentId(), 0)))
                .toList();
    }

    @Override
    public CommentPageResponse listHeld(Long videoId, String cursor, int size, Long currentUserId) {
        requireOwner(videoId, currentUserId);
        return scan(cursor, size,
                pageRequest -> heldCommentRepository.findNewestFirst(videoId, pageRequest)
                        .map(row -> row.getKey().getCommentId()),
                ids -> hydrateHeld(videoId, ids));
    }

    /**
     * A queue row outlives its comment's approval or deletion until the next write cleans it up, so
     * the comment row decides: held and not deleted, or left out. A held reply whose thread was
     * deleted since is left out too — approving it would count a reply nobody can ever see.
     */
    private List<CommentResponse> hydrateHeld(Long videoId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, CommentByVideo> rows = withoutBannedAuthors(
                commentByVideoRepository.findAllByCommentIds(videoId, ids).stream()
                        .filter(comment -> comment.isHeld() && !comment.isDeleted())
                        .collect(Collectors.toMap(comment -> comment.getKey().getCommentId(), Function.identity())));

        List<Long> parentIds = rows.values().stream()
                .map(CommentByVideo::getParentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Set<Long> liveParents = parentIds.isEmpty()
                ? Set.of()
                : commentByVideoRepository.findAllByCommentIds(videoId, parentIds).stream()
                        .filter(CommentByVideo::isLive)
                        .map(comment -> comment.getKey().getCommentId())
                        .collect(Collectors.toSet());

        return ids.stream()
                .map(rows::get)
                .filter(Objects::nonNull)
                .filter(comment -> comment.getParentId() == null || liveParents.contains(comment.getParentId()))
                .map(commentMapper::toResponse)
                .toList();
    }

    @Override
    public CommentResponse approveComment(Long videoId, Long commentId, Long currentUserId) {
        requireOwner(videoId, currentUserId);
        CommentByVideo comment = findComment(videoId, commentId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new CommentNotFoundException(commentId));
        if (!comment.isHeld()) {
            return commentMapper.toResponse(comment);
        }

        Long parentAuthorId = null;
        if (comment.getParentId() != null) {
            CommentByVideo parent = findComment(videoId, comment.getParentId())
                    .filter(CommentByVideo::isLive)
                    .orElse(null);
            if (parent == null) {
                // The thread it answers is gone, so it can never be shown; drop it rather than
                // leave it in the queue for another try.
                deleteHeld(comment);
                throw new CommentNotFoundException(commentId);
            }
            // Who the reply answers, as addComment worked it out: the reply it targeted if that is
            // recorded, otherwise the top-level comment.
            parentAuthorId = comment.getReplyToUserId() != null ? comment.getReplyToUserId() : parent.getUserId();
            // addComment refuses a reply across a block, and one may have come since this was
            // posted. Dropped like a reply to a deleted thread, and as quietly: a block between two
            // other people is not the owner's to learn of.
            if (blockClient.isBlockedBetween(parentAuthorId, comment.getUserId())) {
                deleteHeld(comment);
                throw new CommentNotFoundException(commentId);
            }
        }

        // Counted before it can be seen. From the moment it is VISIBLE a delete takes it off the
        // counters, so it has to be on them already: counted after, a delete landing in between would
        // take down a count this comment never put up.
        count(comment);
        boolean approved = false;
        try {
            approved = commentByVideoRepository.approveIfHeld(videoId, commentId);
        } finally {
            if (!approved) {
                uncount(comment);
            }
        }
        if (!approved) {
            // Somebody decided first: approved from another device, which counted it itself, or
            // deleted. The row says which.
            return findComment(videoId, commentId)
                    .filter(CommentByVideo::isLive)
                    .map(commentMapper::toResponse)
                    .orElseThrow(() -> new CommentNotFoundException(commentId));
        }
        try {
            listAndPublish(comment, parentAuthorId);
        } catch (RuntimeException ex) {
            // Back to the queue and off the counters — unless a delete got to it first, which took it
            // off them itself.
            if (commentByVideoRepository.holdAgainIfVisible(videoId, commentId)) {
                uncount(comment);
            }
            throw ex;
        }
        dropFromQueue(videoId, commentId);
        return commentMapper.toResponse(comment).withStatus(CommentStatus.VISIBLE);
    }

    /**
     * Fails closed: the owner's queue is text nobody has cleared, so an owner video-service cannot
     * confirm is refused like a stranger — the reverse of the comment guard, which fails open.
     */
    private void requireOwner(Long videoId, Long currentUserId) {
        if (!videoOwnershipClient.isOwnedBy(videoId, currentUserId)) {
            throw new NotVideoOwnerException(videoId);
        }
    }

    private Optional<CommentByVideo> findComment(Long videoId, Long commentId) {
        return commentByVideoRepository.findById(CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build());
    }

    /**
     * Best effort: the queue's reader checks the comment row and skips anything no longer held, so a
     * row left behind is a wasted read, not a comment shown twice.
     */
    private void dropFromQueue(Long videoId, Long commentId) {
        try {
            heldCommentRepository.deleteById(HeldCommentKey.builder().videoId(videoId).commentId(commentId).build());
        } catch (RuntimeException ex) {
            log.warn("Held comment {} on video {} left in the review queue: {}", commentId, videoId, ex.getMessage());
        }
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

        if (!softDelete(comment, currentUserId)) {
            throw new CommentNotFoundException(commentId);
        }
        cascadeToReplies(comment);
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
        if (softDelete(comment, comment.getUserId())) {
            // A strike against the author — counted by the call that deleted it, so a redelivered
            // event does not strike twice. The replies the cascade takes down are not theirs to
            // answer for.
            commentStrikes.strike(comment.getUserId(), "comment " + commentId + " removed by an admin");
            cascadeToReplies(comment);
        }
    }

    /**
     * Takes a thread's replies down with its top-level comment. A reply whose parent is gone can
     * never be rendered — the flat list hangs replies off a parent, so an orphan is invisible —
     * but it stays in the counter, which is how a video ends up reading "1 comments" over an empty
     * list. Nesting is one level deep (see addComment), so this never recurses.
     *
     * <p>Best effort, one reply at a time: each reply owns its own LWT, counter decrement and
     * event, and a reply that fails rolls only itself back. Failing the caller here would be worse
     * than a stale count — the comment they asked to delete is already gone.
     */
    private void cascadeToReplies(CommentByVideo parent) {
        if (parent.getParentId() != null) {
            return;
        }
        Long videoId = parent.getKey().getVideoId();
        Long parentId = parent.getKey().getCommentId();

        // Paged rather than read whole: a thread's replies are unbounded, and the index hands them
        // over a partition at a time. Deleting as we go does not move the cursor — the index rows
        // stay, only the comments behind them are tombstoned.
        CassandraPageRequest pageRequest = CassandraPageRequest.first(CASCADE_PAGE_SIZE);
        while (true) {
            Slice<CommentIndex> slice = commentIndexRepository.findOldestFirst(videoId, parentId, pageRequest);
            List<Long> ids = slice.getContent().stream().map(row -> row.getKey().getCommentId()).toList();
            for (CommentByVideo reply : ids.isEmpty()
                    ? List.<CommentByVideo>of()
                    : commentByVideoRepository.findAllByCommentIds(videoId, ids)) {
                if (reply.isDeleted()) {
                    continue;
                }
                Long replyId = reply.getKey().getCommentId();
                try {
                    softDelete(reply, reply.getUserId());
                } catch (RuntimeException ex) {
                    log.warn("Reply {} of deleted comment {} on video {} left behind: {}",
                            replyId, parentId, videoId, ex.getMessage());
                }
            }
            if (!slice.hasNext()) {
                return;
            }
            pageRequest = (CassandraPageRequest) slice.nextPageable();
        }
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
    private boolean softDelete(CommentByVideo comment, Long userId) {
        if (comment.isHeld()) {
            if (deleteHeld(comment)) {
                return true;
            }
            // Approved between the caller's read and this write, and counted by that approval:
            // delete it as the visible comment it now is.
            Optional<CommentByVideo> approved = findComment(comment.getKey().getVideoId(), comment.getKey().getCommentId())
                    .filter(CommentByVideo::isLive);
            if (approved.isEmpty()) {
                return false;
            }
            comment = approved.get();
        }
        Long videoId = comment.getKey().getVideoId();
        Long commentId = comment.getKey().getCommentId();
        Long parentId = comment.getParentId();

        Instant deletedAt = Instant.now();
        if (!commentByVideoRepository.markDeletedIfNotDeleted(videoId, commentId, deletedAt)) {
            return false;
        }

        boolean countered = false;
        boolean replyCounted = false;
        try {
            if (parentId != null) {
                commentCountersRepository.incrementReplyCount(videoId, parentId, -1);
                replyCounted = true;
            }
            videoCountersRepository.incrementCommentCount(videoId, -1);
            countered = true;
            counterCacheService.invalidate(videoId);
            eventPublisher.publishCommentDeleted(commentId, videoId, userId);
        } catch (RuntimeException ex) {
            if (countered) {
                undoCounter(videoId, 1);
            }
            if (replyCounted) {
                undoReplyCounter(videoId, parentId, 1);
            }
            commentByVideoRepository.restoreIfDeletedAt(videoId, commentId, deletedAt);
            throw ex;
        }
        return true;
    }

    /**
     * A held comment was never counted and never announced, so deleting one moves no counter and
     * tells nobody — the counters and the event are for comments that were visible. Conditioned on it
     * still being held: see {@link CommentByVideoRepository#markDeletedIfHeld}.
     */
    private boolean deleteHeld(CommentByVideo comment) {
        Long videoId = comment.getKey().getVideoId();
        Long commentId = comment.getKey().getCommentId();
        if (!commentByVideoRepository.markDeletedIfHeld(videoId, commentId, Instant.now())) {
            return false;
        }
        dropFromQueue(videoId, commentId);
        return true;
    }

    @Override
    public CommentLikeResponse likeComment(Long videoId, Long commentId, Long currentUserId) {
        videoOwnershipClient.requireVisible(videoId);
        // Keyed by comment, not by video: a viewer working through a busy comment section likes
        // many different comments, and one shared per-video budget would refuse them for reading.
        rateLimiter.require("comment-like-rate", commentId, currentUserId, CommentLikeRateLimitedException::new);
        CommentByVideo comment = liveComment(videoId, commentId);

        // The membership LWT is what grants the right to move the count, and it grants it once:
        // a retried like finds the row already there, is told newlyLiked=false, and leaves the
        // tally alone. Through LwtRetry for the one retry it cannot tell apart on its own: an attempt
        // that timed out after it applied. Truncated to what Cassandra stores, so that retry can
        // recognise the row as this call's own.
        Instant likedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        CommentLikeKey key = CommentLikeKey.builder().commentId(commentId).userId(currentUserId).build();
        boolean newlyLiked = LwtRetry.run(
                () -> commentLikeRepository.insertIfNotExists(commentId, currentUserId, likedAt),
                () -> commentLikeRepository.findById(key)
                        .filter(row -> likedAt.equals(row.getCreatedAt())).isPresent(),
                "Comment like/unlike");
        int likeCount = newlyLiked ? moveLikes(videoId, commentId, comment, 1) : comment.likeCount();
        // Only on an actual change: a retried like moved nothing, and announcing it would wake
        // every open panel to redraw the number it already shows.
        if (newlyLiked) {
            eventPublisher.publishCommentLikeChanged(commentId, videoId, currentUserId, true, likeCount,
                    comment.getUserId());
        }
        return new CommentLikeResponse(commentId, true, likeCount);
    }

    @Override
    public CommentLikeResponse unlikeComment(Long videoId, Long commentId, Long currentUserId) {
        // Same bucket as the like above, so a like/unlike cycle spends two of one budget rather
        // than one of each and never runs out.
        rateLimiter.require("comment-like-rate", commentId, currentUserId, CommentLikeRateLimitedException::new);
        CommentByVideo comment = liveComment(videoId, commentId);

        boolean wasLiked = LwtRetry.runDelete(
                () -> commentLikeRepository.deleteIfExists(commentId, currentUserId), "Comment like/unlike");
        int likeCount = wasLiked ? moveLikes(videoId, commentId, comment, -1) : comment.likeCount();
        if (wasLiked) {
            eventPublisher.publishCommentLikeChanged(commentId, videoId, currentUserId, false, likeCount,
                    comment.getUserId());
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

    /** Neither deleted nor held: nobody likes a comment nobody can read yet. */
    private CommentByVideo liveComment(Long videoId, Long commentId) {
        return findComment(videoId, commentId)
                .filter(CommentByVideo::isLive)
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

    /** Same compensation as {@link #undoCounter}, for the per-comment reply tally. */
    private void undoReplyCounter(Long videoId, Long parentId, long delta) {
        try {
            commentCountersRepository.incrementReplyCount(videoId, parentId, delta);
        } catch (RuntimeException e) {
            log.error("Could not take back the reply count change on comment {} of video {}; it is "
                    + "now off by one", parentId, videoId, e);
        }
    }
}
