package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.dto.request.AdminCommentFilter;
import com.tiktok.interactionservice.dto.response.AdminCommentPageResponse;
import com.tiktok.interactionservice.dto.response.AdminCommentResponse;
import com.tiktok.interactionservice.entity.CommentByVideo;
import com.tiktok.interactionservice.entity.CommentCounters;
import com.tiktok.interactionservice.entity.CommentIndex;
import com.tiktok.interactionservice.exception.InvalidCommentCursorException;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentCountersRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.cassandra.CassandraInvalidQueryException;
import org.springframework.data.cassandra.core.query.CassandraPageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The console's read side over a video's comment thread.
 *
 * <p>Per video, because that is the only way Cassandra can be asked: both tables behind this are
 * partitioned by video id, and a platform-wide "newest comments" listing would need another table
 * written on every comment. Nothing today justifies that — moderation arrives either from a report,
 * which names its video, or from a video already on screen.
 *
 * <p>Two ways in, because the two questions have different shapes:
 *
 * <ul>
 *   <li>The thread view reads {@code comment_index}, one partition per (video, parent), the same
 *       way {@link CommentServiceImpl} does: top-level comments and one comment's replies are each
 *       a single-partition read, so a thread of two hundred replies never lands in the page of
 *       top-level comments.
 *   <li>The Replies and Removed views read the {@code comments_by_video} partition and filter it,
 *       because neither question has an index and neither is worth one. Removal would have to
 *       write a second index row on a path that already does an LWT plus two counter updates, and
 *       "every reply on this video" spans every reply partition at once. Filtering a paged scan
 *       costs the admin a few extra reads on a screen nobody is watching latency on; the writes it
 *       would otherwise slow down are on every viewer's hot path.
 * </ul>
 *
 * <p>What is deliberately <em>not</em> shared with the public listing is the dropping of removed
 * comments. There, they are filtered out after Cassandra has cut the page. Here they are the point
 * — an admin reviewing a thread has to see what was already taken down — and the owner's
 * comments-off switch is not consulted either, for the same reason: it hides rows from viewers,
 * not from the admin reviewing them.
 */
@Service
@RequiredArgsConstructor
public class AdminCommentDirectory {

    /**
     * How many pages a filtered scan may walk before handing back an empty one with a cursor.
     * Filtering happens after Cassandra has cut the page, so a video with thousands of consecutive
     * comments that do not match would otherwise scan its whole partition inside one request.
     */
    private static final int MAX_PAGES_SCANNED = 5;

    private final CommentByVideoRepository commentByVideoRepository;
    private final CommentIndexRepository commentIndexRepository;
    private final CommentCountersRepository commentCountersRepository;

    /**
     * One page of a video's comments, newest first — which comments depends on the filter. Removed
     * ones are included in every view; see the class note.
     */
    public AdminCommentPageResponse listForAdmin(Long videoId, AdminCommentFilter filter,
                                                 String cursor, int size) {
        return switch (filter) {
            case THREAD -> indexPage(videoId, CommentIndex.TOP_LEVEL, cursor, size);
            case REPLIES -> scanPage(videoId, comment -> comment.getParentId() != null, cursor, size);
            case REMOVED -> scanPage(videoId, CommentByVideo::isDeleted, cursor, size);
        };
    }

    /**
     * One page of one comment's replies, oldest first, which is the order the conversation
     * happened in. No existence check on the parent: an unknown id is an empty partition, which is
     * the same empty page a comment with no replies gives, and one fewer read on the common path.
     */
    public AdminCommentPageResponse listRepliesForAdmin(Long videoId, Long parentId, String cursor, int size) {
        return indexPage(videoId, parentId, cursor, size);
    }

    /**
     * Specific comments by id, for the parent context the console shows above a reply: the Replies
     * and Removed views are flat, so the comment a reply answers is usually not on the page beside
     * it, and "Reply to @someone" without the comment being answered is what this whole screen
     * exists to fix. One bounded read — the video is fixed and the ids cluster inside its
     * partition. Ids with no row are left out rather than faulted; the caller renders the reply
     * without a preview.
     */
    public List<AdminCommentResponse> listByIds(Long videoId, List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, CommentByVideo> rows = rowsById(videoId, ids);
        List<CommentByVideo> ordered = ids.stream().map(rows::get).filter(Objects::nonNull).toList();
        // No slice to page, and a parent preview needs neither tally: it is the comment above,
        // not a thread to open.
        return ordered.stream().map(comment -> toResponse(comment, 0, false)).toList();
    }

    /** One thread's ids out of {@code comment_index}, hydrated in the order the index gave them. */
    private AdminCommentPageResponse indexPage(Long videoId, long threadId, String cursor, int size) {
        boolean topLevel = threadId == CommentIndex.TOP_LEVEL;
        CassandraPageRequest pageRequest =
                CassandraCursors.decode(cursor, size, InvalidCommentCursorException::new);

        Slice<CommentIndex> slice;
        try {
            slice = topLevel
                    ? commentIndexRepository.findNewestFirst(videoId, threadId, pageRequest)
                    : commentIndexRepository.findOldestFirst(videoId, threadId, pageRequest);
        } catch (CassandraInvalidQueryException e) {
            throw cursorOrOriginal(cursor, e);
        }

        List<Long> ids = slice.getContent().stream().map(row -> row.getKey().getCommentId()).toList();
        // In the index's order, not the table's: findAllByCommentIds answers in the table's own
        // clustering order, which for a reply thread is the reverse of the page asked for.
        Map<Long, CommentByVideo> rows = rowsById(videoId, ids);
        List<CommentByVideo> ordered = ids.stream().map(rows::get).filter(Objects::nonNull).toList();

        return page(videoId, ordered, slice);
    }

    /**
     * One page of the {@code comments_by_video} partition, narrowed to what the filter keeps.
     *
     * <p>Filtering after Cassandra has cut the page means a page can come back empty while there
     * is more behind it; a client that stops on an empty page would stop early. Pages are walked
     * here until one has something in it, so that loop lives in one place rather than in every
     * caller — the same reasoning, and the same bound, as the public listing's.
     */
    private AdminCommentPageResponse scanPage(Long videoId, Predicate<CommentByVideo> keep,
                                              String cursor, int size) {
        CassandraPageRequest pageRequest =
                CassandraCursors.decode(cursor, size, InvalidCommentCursorException::new);

        for (int scanned = 0; scanned < MAX_PAGES_SCANNED; scanned++) {
            Slice<CommentByVideo> slice;
            try {
                slice = commentByVideoRepository.findByVideoId(videoId, pageRequest);
            } catch (CassandraInvalidQueryException e) {
                throw cursorOrOriginal(scanned == 0 ? cursor : null, e);
            }

            // The partition clusters comment_id descending, so the page is already newest first.
            List<CommentByVideo> kept = slice.getContent().stream().filter(keep).toList();
            if (!kept.isEmpty() || !slice.hasNext()) {
                return page(videoId, kept, slice);
            }
            pageRequest = (CassandraPageRequest) slice.nextPageable();
        }

        return new AdminCommentPageResponse(List.of(), CassandraCursors.encode(pageRequest), true);
    }

    /**
     * Wraps the rows a page kept, filling in the reply tallies for the top-level comments among
     * them — two bounded reads over one video, and neither is issued for a page that holds only
     * replies.
     */
    private AdminCommentPageResponse page(Long videoId, List<CommentByVideo> rows, Slice<?> slice) {
        List<Long> topLevelIds = rows.stream()
                .filter(comment -> comment.getParentId() == null)
                .map(comment -> comment.getKey().getCommentId())
                .toList();

        Map<Long, Integer> replyCounts = topLevelIds.isEmpty() ? Map.of() : replyCounts(videoId, topLevelIds);
        Set<Long> withReplies = topLevelIds.isEmpty() ? Set.of() : threadsWithReplies(videoId, topLevelIds);

        List<AdminCommentResponse> items = rows.stream()
                .map(comment -> {
                    Long commentId = comment.getKey().getCommentId();
                    return toResponse(
                            comment,
                            replyCounts.getOrDefault(commentId, 0),
                            withReplies.contains(commentId));
                })
                .toList();

        boolean hasMore = slice.hasNext();
        return new AdminCommentPageResponse(
                items,
                hasMore ? CassandraCursors.encode((CassandraPageRequest) slice.nextPageable()) : null,
                hasMore);
    }

    /**
     * The rows behind a page of ids. An id with no row is left out: {@link
     * CommentServiceImpl#addComment} rolls its index row back when the comment itself could not be
     * stored, and a page must not fail over the window in which only one of the two writes landed.
     */
    private Map<Long, CommentByVideo> rowsById(Long videoId, List<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return commentByVideoRepository.findAllByCommentIds(videoId, ids).stream()
                .collect(Collectors.toMap(comment -> comment.getKey().getCommentId(), Function.identity()));
    }

    /** "3 replies" for a whole page in one counter read — live replies only, see the DTO. */
    private Map<Long, Integer> replyCounts(Long videoId, List<Long> ids) {
        return commentCountersRepository.findAllByCommentIds(videoId, ids).stream()
                .collect(Collectors.toMap(row -> row.getKey().getCommentId(), CommentCounters::replies));
    }

    /** Which of these comments have replies at all, removed ones counted. One bounded read. */
    private Set<Long> threadsWithReplies(Long videoId, List<Long> ids) {
        return commentIndexRepository.findThreadsWithReplies(videoId, ids).stream()
                .map(row -> row.getKey().getParentId())
                .collect(Collectors.toSet());
    }

    /**
     * Base64 that decodes into bytes Cassandra will not accept as paging state is refused by the
     * coordinator rather than by the decoder, and only a cursor the client supplied can be at
     * fault — every later page uses one this class issued.
     */
    private static RuntimeException cursorOrOriginal(String cursor, CassandraInvalidQueryException e) {
        return cursor != null && !cursor.isBlank() ? new InvalidCommentCursorException() : e;
    }

    private static AdminCommentResponse toResponse(CommentByVideo comment, int replyCount, boolean hasReplies) {
        return new AdminCommentResponse(
                comment.getKey().getCommentId(),
                comment.getKey().getVideoId(),
                comment.getUserId(),
                comment.getContent(),
                comment.getParentId(),
                comment.getReplyToUserId(),
                comment.likeCount(),
                replyCount,
                hasReplies,
                comment.getCreatedAt(),
                comment.getDeletedAt());
    }
}
