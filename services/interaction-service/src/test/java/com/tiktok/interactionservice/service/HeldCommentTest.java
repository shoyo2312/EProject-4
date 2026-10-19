package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.BlockClient;
import com.tiktok.interactionservice.client.CommentAudience;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentByVideoKey;
import com.tiktok.interactionservice.entity.CommentStatus;
import com.tiktok.interactionservice.entity.VideoCounters;
import com.tiktok.interactionservice.exception.CommentNotFoundException;
import com.tiktok.interactionservice.exception.CommentRejectedException;
import com.tiktok.interactionservice.exception.EventNotPublishedException;
import com.tiktok.interactionservice.exception.NotVideoOwnerException;
import com.tiktok.interactionservice.exception.ReplyNotAllowedException;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import com.tiktok.interactionservice.repository.HeldCommentRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the text rules do to a comment — refuse it, or hold it for the video's owner — and how a
 * held comment stays out of everything a visible one touches until it is approved.
 */
class HeldCommentTest extends AbstractInteractionServiceIT {

    private static final Long OWNER = 9L;
    private static final Long AUTHOR = 1L;
    private static final String SPAM = "vào abc.xyz nhận quà nhé";

    @Autowired
    private CommentService commentService;

    @Autowired
    private CommentByVideoRepository commentByVideoRepository;

    @Autowired
    private CommentIndexRepository commentIndexRepository;

    @Autowired
    private HeldCommentRepository heldCommentRepository;

    @Autowired
    private VideoCountersRepository videoCountersRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private VideoOwnershipClient videoOwnershipClient;

    @MockitoBean
    private BlockClient blockClient;

    @BeforeEach
    void cleanUp() {
        commentByVideoRepository.deleteAll();
        commentIndexRepository.deleteAll();
        heldCommentRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();

        when(videoOwnershipClient.commentPolicy(anyLong()))
                .thenReturn(new CommentPolicy(OWNER, false, CommentAudience.EVERYONE));
        when(videoOwnershipClient.isOwnedBy(anyLong(), eq(OWNER))).thenReturn(true);
    }

    private long commentCount(Long videoId) {
        return videoCountersRepository.findById(videoId).map(VideoCounters::getCommentCount).orElse(0L);
    }

    @Test
    void aRefusedComment_isNotStored() {
        assertThatThrownBy(() -> commentService.addComment(100L, AUTHOR, "địt mẹ mày"))
                .isInstanceOf(CommentRejectedException.class);

        assertThat(commentByVideoRepository.findAll()).isEmpty();
        assertThat(commentCount(100L)).isZero();
    }

    /**
     * Stored for the owner, and nothing else: not listed, not counted, not announced — chat would
     * otherwise push it to every open comment panel the moment it was written.
     */
    @Test
    void aHeldComment_isStoredButExistsForNobodyElse() {
        clearInvocations(kafkaTemplate);

        CommentResponse held = commentService.addComment(101L, AUTHOR, SPAM);

        assertThat(held.status()).isEqualTo(CommentStatus.HELD);
        assertThat(commentService.listComments(101L, null, 20).items()).isEmpty();
        assertThat(commentCount(101L)).isZero();
        verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
        assertThat(commentByVideoRepository.findById(key(101L, held.commentId()))).get()
                .satisfies(row -> {
                    assertThat(row.getModReason()).isEqualTo("SPAM:link:abc.xyz");
                    assertThat(row.getModVersion()).startsWith("rules-");
                });
    }

    @Test
    void theOwnersOwnComment_isNeverHeld() {
        assertThat(commentService.addComment(102L, OWNER, SPAM).status()).isEqualTo(CommentStatus.VISIBLE);
    }

    @Test
    void aHeldComment_cannotBeLikedOrRepliedTo() {
        CommentResponse held = commentService.addComment(103L, AUTHOR, SPAM);

        assertThatThrownBy(() -> commentService.likeComment(103L, held.commentId(), 2L))
                .isInstanceOf(CommentNotFoundException.class);
        assertThatThrownBy(() -> commentService.addComment(103L, 2L, "đồng ý", held.commentId()))
                .isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    void theQueue_isTheOwnersAlone() {
        CommentResponse held = commentService.addComment(104L, AUTHOR, SPAM);
        commentService.addComment(104L, 2L, "video hay quá");

        assertThat(commentService.listHeld(104L, null, 20, OWNER).items())
                .extracting(CommentResponse::commentId)
                .containsExactly(held.commentId());
        assertThatThrownBy(() -> commentService.listHeld(104L, null, 20, AUTHOR))
                .isInstanceOf(NotVideoOwnerException.class);
    }

    /** An owner video-service cannot confirm is refused like a stranger: the queue is uncleared text. */
    @Test
    void theQueue_failsClosed() {
        when(videoOwnershipClient.isOwnedBy(anyLong(), eq(OWNER))).thenReturn(false);

        assertThatThrownBy(() -> commentService.listHeld(105L, null, 20, OWNER))
                .isInstanceOf(NotVideoOwnerException.class);
        assertThatThrownBy(() -> commentService.approveComment(105L, 1L, OWNER))
                .isInstanceOf(NotVideoOwnerException.class);
    }

    @Test
    void approval_publishesTheCommentOnce_evenTappedTwice() {
        CommentResponse held = commentService.addComment(106L, AUTHOR, SPAM);

        CommentResponse approved = commentService.approveComment(106L, held.commentId(), OWNER);
        CommentResponse again = commentService.approveComment(106L, held.commentId(), OWNER);

        assertThat(approved.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(again.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(commentService.listComments(106L, null, 20).items())
                .extracting(CommentResponse::commentId)
                .containsExactly(held.commentId());
        assertThat(commentCount(106L)).isEqualTo(1L);
        assertThat(commentService.listHeld(106L, null, 20, OWNER).items()).isEmpty();
        assertThat(heldCommentRepository.findAll()).isEmpty();
    }

    @Test
    void approvingAReply_countsItAsAReply() {
        CommentResponse thread = commentService.addComment(107L, 2L, "video hay quá");
        CommentResponse heldReply = commentService.addComment(107L, AUTHOR, SPAM, thread.commentId());

        commentService.approveComment(107L, heldReply.commentId(), OWNER);

        assertThat(commentService.listComments(107L, null, 20).items().getFirst().replyCount()).isEqualTo(1);
        assertThat(commentService.listReplies(107L, thread.commentId(), null, 20).items())
                .extracting(CommentResponse::commentId)
                .containsExactly(heldReply.commentId());
    }

    /** The approval took the comment out of the queue; a broker that refused its event puts it back. */
    @Test
    void approval_whoseAnnouncementFails_leavesTheCommentHeldAndUncounted() {
        CommentResponse held = commentService.addComment(108L, AUTHOR, SPAM);
        when(kafkaTemplate.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        assertThatThrownBy(() -> commentService.approveComment(108L, held.commentId(), OWNER))
                .isInstanceOf(EventNotPublishedException.class);

        assertThat(commentByVideoRepository.findById(key(108L, held.commentId()))).get()
                .extracting(row -> row.effectiveStatus()).isEqualTo(CommentStatus.HELD);
        assertThat(commentCount(108L)).isZero();
        assertThat(commentService.listComments(108L, null, 20).items()).isEmpty();
    }

    /**
     * A held comment was never counted. Deleting one through the visible path would take the count
     * down for something that never put it up — and a counter table has no way back.
     */
    @Test
    void deletingAHeldComment_leavesTheCountAlone_whoeverDeletesIt() {
        commentService.addComment(109L, 2L, "video hay quá");
        CommentResponse byAuthor = commentService.addComment(109L, AUTHOR, SPAM);
        CommentResponse byOwner = commentService.addComment(109L, 3L, "ghé abc.xyz");
        CommentResponse byAdmin = commentService.addComment(109L, 4L, "xem tại abc.xyz");

        commentService.deleteComment(109L, byAuthor.commentId(), AUTHOR);
        commentService.deleteComment(109L, byOwner.commentId(), OWNER);
        commentService.removeByAdmin(109L, byAdmin.commentId());

        assertThat(commentCount(109L)).isEqualTo(1L);
        assertThat(commentService.listHeld(109L, null, 20, OWNER).items()).isEmpty();
        assertThatThrownBy(() -> commentService.approveComment(109L, byAuthor.commentId(), OWNER))
                .isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    void aHeldReplyWhoseThreadWasDeleted_isLeftOutOfTheQueue_andCannotBeApproved() {
        CommentResponse thread = commentService.addComment(110L, 2L, "video hay quá");
        CommentResponse heldReply = commentService.addComment(110L, AUTHOR, SPAM, thread.commentId());
        commentService.deleteComment(110L, thread.commentId(), 2L);

        assertThat(commentService.listHeld(110L, null, 20, OWNER).items()).isEmpty();
        assertThatThrownBy(() -> commentService.approveComment(110L, heldReply.commentId(), OWNER))
                .isInstanceOf(CommentNotFoundException.class);
        assertThat(commentCount(110L)).isZero();
    }

    @Test
    void aReplyToSomeoneWhoBlockedTheCaller_isRefused_andNotStored() {
        CommentResponse thread = commentService.addComment(111L, 2L, "video hay quá");
        when(blockClient.isBlockedBetween(2L, AUTHOR)).thenReturn(true);

        assertThatThrownBy(() -> commentService.addComment(111L, AUTHOR, "đồng ý", thread.commentId()))
                .isInstanceOf(ReplyNotAllowedException.class);

        assertThat(commentService.listReplies(111L, thread.commentId(), null, 20).items()).isEmpty();
        // A top-level comment on the same video is not a reply to them, and still goes through.
        assertThat(commentService.addComment(111L, AUTHOR, "đồng ý").status()).isEqualTo(CommentStatus.VISIBLE);
    }

    /**
     * A block since the reply was held: addComment would refuse it now, so approving it must not
     * publish it either. It goes the way a reply to a deleted thread goes — out of the queue, reported
     * gone, with nothing said to the owner about who blocked whom.
     */
    @Test
    void aHeldReply_acrossABlockThatCameSince_isDroppedRatherThanApproved() {
        CommentResponse thread = commentService.addComment(112L, 2L, "video hay quá");
        CommentResponse heldReply = commentService.addComment(112L, AUTHOR, SPAM, thread.commentId());
        when(blockClient.isBlockedBetween(2L, AUTHOR)).thenReturn(true);

        assertThatThrownBy(() -> commentService.approveComment(112L, heldReply.commentId(), OWNER))
                .isInstanceOf(CommentNotFoundException.class);

        assertThat(commentService.listReplies(112L, thread.commentId(), null, 20).items()).isEmpty();
        assertThat(commentService.listHeld(112L, null, 20, OWNER).items()).isEmpty();
        assertThat(commentCount(112L)).isEqualTo(1L);
    }

    /**
     * A delete can land the moment an approval makes a comment VISIBLE, and it takes a visible comment
     * off the counters — so the approval has to have put it on them first. Here the owner's other
     * device deletes it as its announcement goes out, and the announcement then fails: counted after
     * turning VISIBLE, this used to leave the video a comment short for good.
     */
    @Test
    void aDeleteLandingMidApproval_leavesTheCountRight_evenWhenTheApprovalThenFails() {
        CommentResponse held = commentService.addComment(113L, AUTHOR, SPAM);
        AtomicBoolean deleted = new AtomicBoolean();
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            if (deleted.compareAndSet(false, true)) {
                commentService.deleteComment(113L, held.commentId(), OWNER);
                return CompletableFuture.failedFuture(new IllegalStateException("broker down"));
            }
            return CompletableFuture.completedFuture(null);
        });

        assertThatThrownBy(() -> commentService.approveComment(113L, held.commentId(), OWNER))
                .isInstanceOf(EventNotPublishedException.class);

        assertThat(commentCount(113L)).isZero();
        assertThat(commentService.listComments(113L, null, 20).items()).isEmpty();
        assertThat(commentByVideoRepository.findById(key(113L, held.commentId()))).get()
                .satisfies(row -> assertThat(row.isDeleted()).isTrue());
    }

    private static CommentByVideoKey key(Long videoId, Long commentId) {
        return CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build();
    }
}
