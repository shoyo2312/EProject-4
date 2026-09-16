package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.exception.CommentNotFoundException;
import com.tiktok.interactionservice.exception.CommentsDisabledException;
import com.tiktok.interactionservice.exception.InvalidCommentCursorException;
import com.tiktok.interactionservice.exception.NotCommentOwnerException;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

class CommentServiceImplTest extends AbstractInteractionServiceIT {

    @Autowired
    private CommentService commentService;

    @Autowired
    private CommentByVideoRepository commentByVideoRepository;

    @Autowired
    private CommentIndexRepository commentIndexRepository;

    @Autowired
    private VideoCountersRepository videoCountersRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // Real bean talks to video-service over HTTP; in the IT there is none, so it is mocked. The
    // default answer (false) matches what the unreachable real client returns, so the other tests
    // are unaffected.
    @MockBean
    private VideoOwnershipClient videoOwnershipClient;

    @BeforeEach
    void cleanUp() {
        commentByVideoRepository.deleteAll();
        commentIndexRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void addComment_persistsAndIncrementsCount() {
        CommentResponse response = commentService.addComment(20L, 1L, "hello");

        assertThat(response.content()).isEqualTo("hello");
        assertThat(response.userId()).isEqualTo(1L);
        assertThat(response.videoId()).isEqualTo(20L);
    }

    @Test
    void addComment_asReply_persistsParentIdAndListsInline() {
        CommentResponse parent = commentService.addComment(40L, 1L, "top-level");

        CommentResponse reply = commentService.addComment(40L, 2L, "a reply", parent.commentId());

        assertThat(reply.parentId()).isEqualTo(parent.commentId());
        // Direct reply to a top-level comment: no "A > B" label, the target is the thread owner.
        assertThat(reply.replyToUserId()).isNull();
        // The listing is top-level only; the reply lives behind its parent's own endpoint.
        assertThat(commentService.listComments(40L, null, 20).items())
                .extracting(CommentResponse::commentId)
                .containsExactly(parent.commentId());
        assertThat(commentService.listReplies(40L, parent.commentId(), null, 20).items())
                .singleElement()
                .extracting(CommentResponse::parentId)
                .isEqualTo(parent.commentId());
    }

    @Test
    void addComment_replyToAReply_flattensToTopLevelParent() {
        CommentResponse top = commentService.addComment(41L, 1L, "top");
        CommentResponse first = commentService.addComment(41L, 2L, "first reply", top.commentId());

        CommentResponse nested = commentService.addComment(41L, 3L, "reply to reply", first.commentId());

        assertThat(nested.parentId()).isEqualTo(top.commentId());
        // Target was itself a reply: its author is recorded for the "A > B" label, and it survives the listing.
        assertThat(nested.replyToUserId()).isEqualTo(2L);
        assertThat(commentService.listReplies(41L, top.commentId(), null, 20).items())
                .filteredOn(c -> c.commentId().equals(nested.commentId()))
                .singleElement()
                .extracting(CommentResponse::replyToUserId)
                .isEqualTo(2L);
    }

    @Test
    void addComment_replyToMissingParent_isRejected() {
        assertThatThrownBy(() -> commentService.addComment(42L, 1L, "orphan", 999999L))
                .isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    void likeComment_persistsCountAndShowsLikedByMeInListing() {
        CommentResponse comment = commentService.addComment(50L, 1L, "like me");

        assertThat(commentService.likeComment(50L, comment.commentId(), 2L).likeCount()).isEqualTo(1);

        CommentResponse listed = commentService.listComments(50L, null, 20, 2L).items().get(0);
        assertThat(listed.likeCount()).isEqualTo(1);
        assertThat(listed.likedByMe()).isTrue();

        // A different viewer sees the count but not their own like.
        assertThat(commentService.listComments(50L, null, 20, 3L).items().get(0).likedByMe()).isFalse();
        // Anonymous listing never reports likedByMe.
        assertThat(commentService.listComments(50L, null, 20, null).items().get(0).likedByMe()).isFalse();
    }

    @Test
    void likeComment_isIdempotent() {
        CommentResponse comment = commentService.addComment(51L, 1L, "spam like");

        commentService.likeComment(51L, comment.commentId(), 2L);
        assertThat(commentService.likeComment(51L, comment.commentId(), 2L).likeCount()).isEqualTo(1);
    }

    @Test
    void unlikeComment_dropsTheCountAndClearsLikedByMe() {
        CommentResponse comment = commentService.addComment(52L, 1L, "toggle");
        commentService.likeComment(52L, comment.commentId(), 2L);

        assertThat(commentService.unlikeComment(52L, comment.commentId(), 2L).likeCount()).isZero();
        assertThat(commentService.listComments(52L, null, 20, 2L).items().get(0).likedByMe()).isFalse();
        // Unliking again is a no-op, not a negative count.
        assertThat(commentService.unlikeComment(52L, comment.commentId(), 2L).likeCount()).isZero();
    }

    /**
     * The tally is a read-modify-write, and the membership LWT does not serialise it: two users
     * liking at the same moment both read the same value and both wrote it back plus one, losing
     * an increment nothing ever reconciles. The write is conditioned on what was read, so the
     * loser has to re-read — which is what this asserts by moving the stored value underneath the
     * caller before the write it is about to make.
     */
    @Test
    void likeComment_writeConditionedOnTheValueItRead() {
        CommentResponse comment = commentService.addComment(54L, 1L, "contended");
        commentService.likeComment(54L, comment.commentId(), 2L);

        // Whoever else is liking this comment got there first; the value this caller read is stale.
        assertThat(commentByVideoRepository.updateLikesIfMatches(54L, comment.commentId(), 7, 1))
                .isTrue();
        assertThat(commentByVideoRepository.updateLikesIfMatches(54L, comment.commentId(), 2, 1))
                .isFalse();

        // A third user's like now builds on 7, not on the 1 the first caller had in hand.
        assertThat(commentService.likeComment(54L, comment.commentId(), 3L).likeCount()).isEqualTo(8);
    }

    @Test
    void likeComment_onMissingComment_isRejected() {
        assertThatThrownBy(() -> commentService.likeComment(53L, 999999L, 1L))
                .isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    void addComment_whenOwnerTurnedCommentsOff_isRejected() {
        when(videoOwnershipClient.areCommentsDisabled(28L)).thenReturn(true);

        assertThatThrownBy(() -> commentService.addComment(28L, 1L, "nope"))
                .isInstanceOf(CommentsDisabledException.class);

        assertThat(commentService.listComments(28L, null, 20).items()).isEmpty();
    }

    @Test
    void listComments_returnsNewestFirst() {
        commentService.addComment(21L, 1L, "first");
        commentService.addComment(21L, 1L, "second");
        commentService.addComment(21L, 1L, "third");

        CommentPageResponse page = commentService.listComments(21L, null, 20);

        assertThat(page.items()).extracting(CommentResponse::content)
                .containsExactly("third", "second", "first");
        assertThat(page.hasMore()).isFalse();
    }

    @Test
    void listComments_paginatesWithCursor() {
        commentService.addComment(22L, 1L, "a");
        commentService.addComment(22L, 1L, "b");
        commentService.addComment(22L, 1L, "c");

        CommentPageResponse firstPage = commentService.listComments(22L, null, 2);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.nextCursor()).isNotBlank();

        CommentPageResponse secondPage = commentService.listComments(22L, firstPage.nextCursor(), 2);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.hasMore()).isFalse();
    }

    /**
     * Deletion is filtered after Cassandra has cut the page, so a page whose rows were all deleted
     * comes back empty next to hasMore=true. A client that stops on an empty page would never see
     * the comments behind it.
     */
    @Test
    void listComments_pageOfOnlyDeletedComments_isSkipped() {
        // Newest first, so the two deleted below are exactly the first page of size 2.
        commentService.addComment(27L, 1L, "still here");
        CommentResponse second = commentService.addComment(27L, 1L, "gone");
        CommentResponse third = commentService.addComment(27L, 1L, "also gone");
        commentService.deleteComment(27L, second.commentId(), 1L);
        commentService.deleteComment(27L, third.commentId(), 1L);

        CommentPageResponse page = commentService.listComments(27L, null, 2);

        assertThat(page.items()).extracting(CommentResponse::content).containsExactly("still here");
    }

    @Test
    void deleteComment_owner_softDeletesAndExcludesFromListing() {
        CommentResponse comment = commentService.addComment(23L, 1L, "to be deleted");

        commentService.deleteComment(23L, comment.commentId(), 1L);

        CommentPageResponse page = commentService.listComments(23L, null, 20);
        assertThat(page.items()).isEmpty();
    }

    /**
     * A reply whose parent is gone can never be rendered — the list hangs replies off a parent —
     * so leaving it counted is how a video ends up reading "1 comments" over an empty list.
     */
    @Test
    void deleteComment_takesItsRepliesAndTheirCountWithIt() {
        CommentResponse parent = commentService.addComment(29L, 1L, "thread starter");
        commentService.addComment(29L, 2L, "reply one", parent.commentId());
        commentService.addComment(29L, 3L, "reply two", parent.commentId());
        CommentResponse other = commentService.addComment(29L, 4L, "unrelated");

        commentService.deleteComment(29L, parent.commentId(), 1L);

        assertThat(commentService.listComments(29L, null, 20).items())
                .extracting(CommentResponse::commentId)
                .containsExactly(other.commentId());
        assertThat(commentService.listReplies(29L, parent.commentId(), null, 20).items()).isEmpty();
        assertThat(videoCountersRepository.findById(29L))
                .get()
                .extracting(counters -> counters.getCommentCount())
                .isEqualTo(1L);
    }

    @Test
    void deleteComment_reply_leavesTheThreadAlone() {
        CommentResponse parent = commentService.addComment(30L, 1L, "thread starter");
        CommentResponse reply = commentService.addComment(30L, 2L, "reply", parent.commentId());

        commentService.deleteComment(30L, reply.commentId(), 2L);

        assertThat(commentService.listComments(30L, null, 20).items())
                .extracting(CommentResponse::commentId)
                .containsExactly(parent.commentId());
        assertThat(videoCountersRepository.findById(30L))
                .get()
                .extracting(counters -> counters.getCommentCount())
                .isEqualTo(1L);
    }

    /** Oldest first, and paged — the client reveals three at a time behind "View N replies". */
    @Test
    void listReplies_readsOldestFirstInPages() {
        CommentResponse parent = commentService.addComment(31L, 1L, "thread starter");
        commentService.addComment(31L, 2L, "one", parent.commentId());
        commentService.addComment(31L, 2L, "two", parent.commentId());
        commentService.addComment(31L, 2L, "three", parent.commentId());

        CommentPageResponse firstPage = commentService.listReplies(31L, parent.commentId(), null, 2);
        assertThat(firstPage.items()).extracting(CommentResponse::content).containsExactly("one", "two");
        assertThat(firstPage.hasMore()).isTrue();

        CommentPageResponse secondPage =
                commentService.listReplies(31L, parent.commentId(), firstPage.nextCursor(), 2);
        assertThat(secondPage.items()).extracting(CommentResponse::content).containsExactly("three");
        assertThat(secondPage.hasMore()).isFalse();
    }

    /** The number behind "View N replies", and it comes down again when a reply is deleted. */
    @Test
    void listComments_carriesTheReplyCount() {
        CommentResponse parent = commentService.addComment(32L, 1L, "thread starter");
        commentService.addComment(32L, 2L, "one", parent.commentId());
        CommentResponse second = commentService.addComment(32L, 2L, "two", parent.commentId());

        assertThat(commentService.listComments(32L, null, 20).items())
                .singleElement()
                .extracting(CommentResponse::replyCount)
                .isEqualTo(2);

        commentService.deleteComment(32L, second.commentId(), 2L);

        assertThat(commentService.listComments(32L, null, 20).items().get(0).replyCount()).isEqualTo(1);
    }

    @Test
    void listReplies_unknownParent_isAnEmptyPage() {
        assertThat(commentService.listReplies(33L, 999999L, null, 20).items()).isEmpty();
    }

    @Test
    void deleteComment_notOwner_throwsForbidden() {
        CommentResponse comment = commentService.addComment(24L, 1L, "mine");

        assertThatThrownBy(() -> commentService.deleteComment(24L, comment.commentId(), 2L))
                .isInstanceOf(NotCommentOwnerException.class);
    }

    /**
     * Both deletes pass the ownership read — it says nothing about whether the comment is still
     * there. Only the conditional write can, and only the caller it applies for may move the
     * counter: a counter table has no way back from a double decrement.
     */
    @Test
    void deleteComment_twice_decrementsTheCountOnce() {
        CommentResponse comment = commentService.addComment(26L, 1L, "delete me twice");
        commentService.deleteComment(26L, comment.commentId(), 1L);

        assertThatThrownBy(() -> commentService.deleteComment(26L, comment.commentId(), 1L))
                .isInstanceOf(CommentNotFoundException.class);

        assertThat(videoCountersRepository.findById(26L))
                .get()
                .extracting(counters -> counters.getCommentCount())
                .isEqualTo(0L);
    }

    /**
     * A cursor the client made up is a 400, not a 500: the decoder throws IllegalArgumentException
     * on anything that is not base64, and the handler of last resort would report that as
     * INTERNAL_ERROR — a query-string typo triaged as an outage.
     */
    @Test
    void listComments_unusableCursor_isRejectedAsBadRequest() {
        assertThatThrownBy(() -> commentService.listComments(28L, "not a cursor!!", 20))
                .isInstanceOf(InvalidCommentCursorException.class);
    }

    @Test
    void deleteComment_unknownComment_throwsNotFound() {
        assertThatThrownBy(() -> commentService.deleteComment(25L, 999L, 1L))
                .isInstanceOf(CommentNotFoundException.class);
    }

    /** The whole point of the admin path: no ownership check, and no video-service round trip. */
    @Test
    void removeByAdmin_removesSomebodyElsesComment() {
        CommentResponse comment = commentService.addComment(30L, 1L, "not the admin's comment");

        commentService.removeByAdmin(30L, comment.commentId());

        assertThat(commentService.listComments(30L, null, 20).items()).isEmpty();
        assertThat(videoCountersRepository.findById(30L))
                .get()
                .extracting(counters -> counters.getCommentCount())
                .isEqualTo(0L);
    }

    /**
     * A redelivered moderation event must not decrement twice. The LWT is what guarantees it —
     * this is the reason the consumer carries no inbox table.
     */
    @Test
    void removeByAdmin_replayed_decrementsTheCountOnce() {
        commentService.addComment(31L, 1L, "stays");
        CommentResponse removed = commentService.addComment(31L, 2L, "goes");

        commentService.removeByAdmin(31L, removed.commentId());
        commentService.removeByAdmin(31L, removed.commentId());

        assertThat(videoCountersRepository.findById(31L))
                .get()
                .extracting(counters -> counters.getCommentCount())
                .isEqualTo(1L);
    }

    /**
     * An event naming a comment this service has never seen is ignored, not thrown: throwing would
     * retry it into the DLQ forever, and the CLAUDE.md consumer contract requires ignoring unknown
     * ids.
     */
    @Test
    void removeByAdmin_unknownComment_isNoOp() {
        commentService.removeByAdmin(32L, 999L);

        assertThat(videoCountersRepository.findById(32L)).isEmpty();
    }
}
