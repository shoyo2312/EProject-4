package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.request.AdminCommentFilter;
import com.tiktok.interactionservice.dto.response.AdminCommentPageResponse;
import com.tiktok.interactionservice.dto.response.AdminCommentResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.exception.InvalidCommentCursorException;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the console reads, and specifically where it must differ from the public listing: removed
 * comments stay in the page, and a long thread pages instead of being cut off at the first fetch.
 */
class AdminCommentDirectoryTest extends AbstractInteractionServiceIT {

    @Autowired
    private AdminCommentDirectory adminCommentDirectory;

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

    // Same reason as CommentServiceImplTest: the real client talks to video-service over HTTP, and
    // the default answer (comments enabled) is what the tests want anyway.
    @MockBean
    private VideoOwnershipClient videoOwnershipClient;

    @BeforeEach
    void cleanUp() {
        commentByVideoRepository.deleteAll();
        commentIndexRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    /** The whole point of the admin read: a comment somebody removed is still on the page. */
    @Test
    void listForAdmin_keepsRemovedComments() {
        CommentResponse kept = commentService.addComment(60L, 1L, "still here");
        CommentResponse removed = commentService.addComment(60L, 2L, "taken down");
        commentService.deleteComment(60L, removed.commentId(), 2L);

        AdminCommentPageResponse page = adminCommentDirectory.listForAdmin(60L, AdminCommentFilter.THREAD, null, 20);

        assertThat(page.items())
                .extracting(AdminCommentResponse::commentId)
                .containsExactly(removed.commentId(), kept.commentId());
        assertThat(page.items().get(0).deletedAt()).isNotNull();
        assertThat(page.items().get(1).deletedAt()).isNull();
    }

    /** Replies live behind their parent, not in the top-level page — that is what keeps it small. */
    @Test
    void listForAdmin_returnsTopLevelOnly() {
        CommentResponse parent = commentService.addComment(61L, 1L, "thread starter");
        commentService.addComment(61L, 2L, "a reply", parent.commentId());

        assertThat(adminCommentDirectory.listForAdmin(61L, AdminCommentFilter.THREAD, null, 20).items())
                .extracting(AdminCommentResponse::commentId)
                .containsExactly(parent.commentId());
    }

    @Test
    void listForAdmin_paginatesWithCursor() {
        commentService.addComment(62L, 1L, "one");
        commentService.addComment(62L, 1L, "two");
        commentService.addComment(62L, 1L, "three");

        AdminCommentPageResponse firstPage = adminCommentDirectory.listForAdmin(62L, AdminCommentFilter.THREAD, null, 2);
        // Newest first, so the page opens on the last thing said.
        assertThat(firstPage.items()).extracting(AdminCommentResponse::content)
                .containsExactly("three", "two");
        assertThat(firstPage.hasMore()).isTrue();

        AdminCommentPageResponse secondPage =
                adminCommentDirectory.listForAdmin(62L, AdminCommentFilter.THREAD, firstPage.nextCursor(), 2);
        assertThat(secondPage.items()).extracting(AdminCommentResponse::content).containsExactly("one");
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.nextCursor()).isNull();
    }

    /** Oldest first and paged, the same shape the viewer-facing panel reveals three at a time. */
    @Test
    void listRepliesForAdmin_readsOldestFirstInPagesAndKeepsRemovedOnes() {
        CommentResponse parent = commentService.addComment(63L, 1L, "thread starter");
        commentService.addComment(63L, 2L, "one", parent.commentId());
        CommentResponse second = commentService.addComment(63L, 2L, "two", parent.commentId());
        commentService.addComment(63L, 2L, "three", parent.commentId());
        commentService.deleteComment(63L, second.commentId(), 2L);

        AdminCommentPageResponse firstPage =
                adminCommentDirectory.listRepliesForAdmin(63L, parent.commentId(), null, 2);
        // "two" is removed and still occupies its place in the thread.
        assertThat(firstPage.items()).extracting(AdminCommentResponse::content)
                .containsExactly("one", "two");
        assertThat(firstPage.hasMore()).isTrue();

        AdminCommentPageResponse secondPage = adminCommentDirectory
                .listRepliesForAdmin(63L, parent.commentId(), firstPage.nextCursor(), 2);
        assertThat(secondPage.items()).extracting(AdminCommentResponse::content).containsExactly("three");
        assertThat(secondPage.hasMore()).isFalse();
    }

    /** The number the console needs to decide whether to offer "View replies" at all. */
    @Test
    void listForAdmin_carriesTheReplyCount() {
        CommentResponse parent = commentService.addComment(64L, 1L, "thread starter");
        commentService.addComment(64L, 2L, "one", parent.commentId());
        commentService.addComment(64L, 2L, "two", parent.commentId());

        assertThat(adminCommentDirectory.listForAdmin(64L, AdminCommentFilter.THREAD, null, 20).items())
                .singleElement()
                .extracting(AdminCommentResponse::replyCount)
                .isEqualTo(2);

        // A reply is never asked for its own replies — the thread is one level deep.
        assertThat(adminCommentDirectory.listRepliesForAdmin(64L, parent.commentId(), null, 20).items())
                .extracting(AdminCommentResponse::replyCount)
                .containsOnly(0);
    }

    @Test
    void listRepliesForAdmin_unknownParent_isAnEmptyPage() {
        AdminCommentPageResponse page = adminCommentDirectory.listRepliesForAdmin(65L, 999999L, null, 20);

        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
    }

    /**
     * The tally says 0 because removing a reply decrements it; the thread must still be openable,
     * or the replies a moderator came to look at are unreachable from the console.
     */
    @Test
    void listForAdmin_threadWhoseRepliesWereAllRemoved_stillReportsHasReplies() {
        CommentResponse parent = commentService.addComment(67L, 1L, "thread starter");
        CommentResponse reply = commentService.addComment(67L, 2L, "the only reply", parent.commentId());
        commentService.deleteComment(67L, reply.commentId(), 2L);

        AdminCommentResponse listed =
                adminCommentDirectory.listForAdmin(67L, AdminCommentFilter.THREAD, null, 20).items().get(0);

        assertThat(listed.replyCount()).isZero();
        assertThat(listed.hasReplies()).isTrue();
        assertThat(adminCommentDirectory.listRepliesForAdmin(67L, parent.commentId(), null, 20).items())
                .singleElement()
                .extracting(AdminCommentResponse::content)
                .isEqualTo("the only reply");
    }

    @Test
    void listForAdmin_commentWithNoReplies_reportsNeither() {
        commentService.addComment(68L, 1L, "nobody answered");

        AdminCommentResponse listed =
                adminCommentDirectory.listForAdmin(68L, AdminCommentFilter.THREAD, null, 20).items().get(0);

        assertThat(listed.replyCount()).isZero();
        assertThat(listed.hasReplies()).isFalse();
    }

    /** Every reply on the video, whichever comment it hangs under — not just the page on screen. */
    @Test
    void listForAdmin_repliesFilter_spansEveryThread() {
        CommentResponse first = commentService.addComment(69L, 1L, "first thread");
        commentService.addComment(69L, 2L, "under first", first.commentId());
        CommentResponse second = commentService.addComment(69L, 1L, "second thread");
        commentService.addComment(69L, 3L, "under second", second.commentId());

        assertThat(adminCommentDirectory.listForAdmin(69L, AdminCommentFilter.REPLIES, null, 20).items())
                .extracting(AdminCommentResponse::content)
                .containsExactly("under second", "under first");
    }

    /** Both levels, and only what was taken down. */
    @Test
    void listForAdmin_removedFilter_coversTopLevelAndReplies() {
        CommentResponse parent = commentService.addComment(70L, 1L, "kept");
        CommentResponse reply = commentService.addComment(70L, 2L, "removed reply", parent.commentId());
        CommentResponse other = commentService.addComment(70L, 3L, "removed comment");
        commentService.deleteComment(70L, reply.commentId(), 2L);
        commentService.deleteComment(70L, other.commentId(), 3L);

        assertThat(adminCommentDirectory.listForAdmin(70L, AdminCommentFilter.REMOVED, null, 20).items())
                .extracting(AdminCommentResponse::content)
                .containsExactlyInAnyOrder("removed comment", "removed reply");
    }

    /**
     * The filter runs after Cassandra has cut the page, so the one match can sit behind pages that
     * hold nothing — the scan walks them rather than handing back an empty page the client stops on.
     */
    @Test
    void listForAdmin_removedFilter_walksPastPagesWithNoMatch() {
        CommentResponse removed = commentService.addComment(71L, 1L, "removed first");
        commentService.deleteComment(71L, removed.commentId(), 1L);
        for (int i = 0; i < 4; i++) {
            commentService.addComment(71L, 2L, "kept " + i);
        }

        AdminCommentPageResponse page =
                adminCommentDirectory.listForAdmin(71L, AdminCommentFilter.REMOVED, null, 2);

        assertThat(page.items()).extracting(AdminCommentResponse::content).containsExactly("removed first");
    }

    /** The parent a reply answers, looked up for the context line — removed parents included. */
    @Test
    void listByIds_returnsTheRequestedComments_andSkipsUnknownIds() {
        CommentResponse parent = commentService.addComment(72L, 1L, "the comment being answered");
        commentService.deleteComment(72L, parent.commentId(), 1L);

        assertThat(adminCommentDirectory.listByIds(72L, List.of(parent.commentId(), 999999L)))
                .singleElement()
                .satisfies(comment -> {
                    assertThat(comment.content()).isEqualTo("the comment being answered");
                    assertThat(comment.deletedAt()).isNotNull();
                });
        assertThat(adminCommentDirectory.listByIds(72L, List.of())).isEmpty();
    }

    /** A made-up cursor is the client's typo — a 400, not the 500 the catch-all would report. */
    @Test
    void listForAdmin_unusableCursor_isRejectedAsBadRequest() {
        assertThatThrownBy(() ->
                adminCommentDirectory.listForAdmin(66L, AdminCommentFilter.THREAD, "not a cursor!!", 20))
                .isInstanceOf(InvalidCommentCursorException.class);
        assertThatThrownBy(() ->
                adminCommentDirectory.listForAdmin(66L, AdminCommentFilter.REMOVED, "not a cursor!!", 20))
                .isInstanceOf(InvalidCommentCursorException.class);
    }
}
