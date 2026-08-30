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
        assertThat(commentService.listComments(40L, null, 20).items())
                .filteredOn(c -> c.commentId().equals(reply.commentId()))
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
        assertThat(commentService.listComments(41L, null, 20).items())
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
}
