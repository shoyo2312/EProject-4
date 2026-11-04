package com.tiktok.interactionservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.BlockClient;
import com.tiktok.interactionservice.client.CommentAudience;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.request.CommentInboxFilter;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentStatus;
import com.tiktok.interactionservice.repository.CommentByOwnerRepository;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import com.tiktok.interactionservice.repository.HeldCommentRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** TikTok Studio's comment inbox: every comment on every video an owner has, in one list. */
class CommentInboxTest extends AbstractInteractionServiceIT {

    private static final Long OWNER = 9L;
    private static final Long OTHER_OWNER = 10L;
    private static final Long AUTHOR = 1L;

    @Autowired
    private CommentService commentService;

    @Autowired
    private CommentByOwnerRepository commentByOwnerRepository;

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
        commentByOwnerRepository.deleteAll();
        commentByVideoRepository.deleteAll();
        commentIndexRepository.deleteAll();
        heldCommentRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();

        when(videoOwnershipClient.commentPolicy(anyLong()))
                .thenReturn(new CommentPolicy(OWNER, false, CommentAudience.EVERYONE));
        when(videoOwnershipClient.commentPolicy(eq(300L)))
                .thenReturn(new CommentPolicy(OTHER_OWNER, false, CommentAudience.EVERYONE));
        when(videoOwnershipClient.isOwnedBy(anyLong(), eq(OWNER))).thenReturn(true);
    }

    @Test
    void theInboxSpansEveryVideoNewestFirst() {
        CommentResponse first = commentService.addComment(100L, AUTHOR, "video đầu tiên hay quá");
        CommentResponse second = commentService.addComment(200L, 2L, "video thứ hai cũng hay");
        commentService.addComment(300L, AUTHOR, "video của người khác");

        assertThat(inbox(CommentInboxFilter.NONE))
                .extracting(CommentResponse::commentId)
                .containsExactly(second.commentId(), first.commentId());
    }

    @Test
    void heldCommentsAreThereForTheOwnerToReview() {
        commentService.addComment(100L, AUTHOR, "vào abc.xyz nhận quà nhé");

        assertThat(inbox(CommentInboxFilter.NONE))
                .singleElement()
                .satisfies(comment -> assertThat(comment.status()).isEqualTo(CommentStatus.HELD));
    }

    @Test
    void theOwnersOwnCommentsAndDeletedOnesAreLeftOut() {
        commentService.addComment(100L, OWNER, "cảm ơn mọi người");
        CommentResponse deleted = commentService.addComment(100L, AUTHOR, "mình sẽ xoá cái này");
        commentService.deleteComment(100L, deleted.commentId(), AUTHOR);

        assertThat(inbox(CommentInboxFilter.NONE)).isEmpty();
    }

    @Test
    void theInboxNarrowsToOneVideoHeldCommentsOrAWord() {
        CommentResponse onFirst = commentService.addComment(100L, AUTHOR, "Hay quá trời luôn");
        CommentResponse onSecond = commentService.addComment(200L, AUTHOR, "nhạc nền tên gì vậy");
        CommentResponse held = commentService.addComment(200L, 2L, "vào abc.xyz nhận quà nhé");

        assertThat(inbox(new CommentInboxFilter(200L, null, null, null, null)))
                .extracting(CommentResponse::commentId).containsExactly(held.commentId(), onSecond.commentId());
        assertThat(inbox(new CommentInboxFilter(null, CommentStatus.HELD, null, null, null)))
                .extracting(CommentResponse::commentId).containsExactly(held.commentId());
        assertThat(inbox(new CommentInboxFilter(null, CommentStatus.VISIBLE, null, null, null)))
                .extracting(CommentResponse::commentId).containsExactly(onSecond.commentId(), onFirst.commentId());
        assertThat(inbox(new CommentInboxFilter(null, null, "HAY QUA", null, null)))
                .extracting(CommentResponse::commentId).containsExactly(onFirst.commentId());
    }

    @Test
    void theInboxNarrowsToAPeriod() {
        Instant start = Instant.now().minusSeconds(1);
        CommentResponse comment = commentService.addComment(100L, AUTHOR, "bình luận hôm nay");
        Instant end = Instant.now().plusSeconds(1);

        assertThat(inbox(new CommentInboxFilter(null, null, null, start, end)))
                .extracting(CommentResponse::commentId).containsExactly(comment.commentId());
        assertThat(inbox(new CommentInboxFilter(null, null, null, end, null))).isEmpty();
        assertThat(inbox(new CommentInboxFilter(null, null, null, null, start))).isEmpty();
    }

    /** The service repeats the generator's private epoch; this is what notices if it moves. */
    @Test
    void aPeriodBoundIsTheFirstIdTheGeneratorHandsOutAtThatTime() {
        Instant before = Instant.now();
        long id = SnowflakeIdGenerator.nextId();
        Instant after = Instant.now().plusMillis(1);

        assertThat(id).isGreaterThanOrEqualTo(CommentServiceImpl.firstCommentIdAt(before))
                .isLessThan(CommentServiceImpl.firstCommentIdAt(after));
    }

    private List<CommentResponse> inbox(CommentInboxFilter filter) {
        return commentService.listForOwner(OWNER, filter, null, 20).items();
    }
}
