package com.tiktok.interactionservice.service;

import com.tiktok.common.id.SnowflakeIdGenerator;
import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.BlockClient;
import com.tiktok.interactionservice.client.CommentAudience;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.UserStatsClient;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.request.CommentInboxFilter;
import com.tiktok.interactionservice.dto.request.CommentInboxFilter.FollowerRange;
import com.tiktok.interactionservice.dto.request.CommentInboxFilter.Sort;
import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentStatus;
import com.tiktok.interactionservice.exception.InboxFilterNeedsVideoException;
import com.tiktok.interactionservice.exception.InvalidCommentCursorException;
import com.tiktok.interactionservice.exception.NotVideoOwnerException;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    @MockitoBean
    private UserStatsClient userStatsClient;

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

    /** Past the generator's reach the bound is the largest id, not an overflow: a far-future "to" is the whole inbox. */
    @Test
    void aPeriodBoundPastTheGeneratorsReach_isTheLargestIdNotAnOverflow() {
        CommentResponse comment = commentService.addComment(100L, AUTHOR, "bình luận hôm nay");

        assertThat(CommentServiceImpl.firstCommentIdAt(Instant.MAX)).isGreaterThan(comment.commentId());
        assertThat(inbox(new CommentInboxFilter(null, null, null, null, Instant.MAX)))
                .extracting(CommentResponse::commentId).containsExactly(comment.commentId());
    }

    /**
     * The thread's deletion cannot cascade to a held reply — held comments are not in the index — so
     * the reply stays HELD. Approving it answers 404, so the inbox must not offer it, as the queue does not.
     */
    @Test
    void aHeldReplyWhoseThreadWasDeleted_isLeftOut() {
        CommentResponse thread = commentService.addComment(100L, 2L, "video hay quá");
        commentService.addComment(100L, AUTHOR, "vào abc.xyz nhận quà nhé", thread.commentId());
        commentService.deleteComment(100L, thread.commentId(), 2L);

        assertThat(inbox(CommentInboxFilter.NONE)).isEmpty();
    }

    @Test
    void aTopLevelComment_saysHowManyRepliesItHas() {
        CommentResponse thread = commentService.addComment(100L, AUTHOR, "video hay quá");
        commentService.addComment(100L, 2L, "đồng ý", thread.commentId());
        commentService.addComment(100L, 3L, "chuẩn luôn", thread.commentId());

        assertThat(inbox(CommentInboxFilter.NONE))
                .filteredOn(comment -> comment.commentId().equals(thread.commentId()))
                .singleElement()
                .extracting(CommentResponse::replyCount).isEqualTo(2);
    }

    /** The reader skips a deleted comment's row, but the row itself goes too: a partition of dead rows is pages of nothing to scan. */
    @Test
    void aDeletedComment_isTakenOutOfTheInboxTable() {
        CommentResponse byAuthor = commentService.addComment(100L, AUTHOR, "mình sẽ xoá cái này");
        CommentResponse held = commentService.addComment(100L, 2L, "vào abc.xyz nhận quà nhé");
        commentService.deleteComment(100L, byAuthor.commentId(), AUTHOR);
        commentService.removeByAdmin(100L, held.commentId());

        assertThat(inbox(CommentInboxFilter.NONE)).isEmpty();
        assertThat(commentByOwnerRepository.findAll()).isEmpty();
    }

    /**
     * Narrowed to one video, the inbox reads that video's own comments: sifting the owner's partition
     * stops after a few pages, and a busy account's other videos push the one asked for past them.
     */
    @Test
    void narrowedToOneVideo_theInboxFindsItsComments_howeverBusyTheOtherVideosAre() {
        CommentResponse first = commentService.addComment(100L, AUTHOR, "bình luận đầu");
        CommentResponse second = commentService.addComment(100L, 2L, "bình luận thứ hai");
        for (int i = 0; i < 60; i++) {
            commentService.addComment(200L, AUTHOR, "video khác " + i);
        }

        assertThat(commentService.listForOwner(OWNER, new CommentInboxFilter(100L, null, null, null, null), null, 10)
                .items())
                .extracting(CommentResponse::commentId)
                .containsExactly(second.commentId(), first.commentId());
    }

    /**
     * A word is sifted out of the owner's partition in memory, so one raw page may hold a single hit:
     * the scan keeps reading until the page is full of hits, not just non-empty.
     */
    @Test
    void narrowedToAWord_aPageFillsUpAcrossRawPages() {
        List<Long> hits = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            String text = i % 10 == 0 ? "phía xa có xe " + i : "bình luận " + i;
            CommentResponse comment = commentService.addComment(100L, AUTHOR, text);
            if (i % 10 == 0) hits.add(comment.commentId());
        }

        CommentPageResponse page = commentService.listForOwner(OWNER,
                new CommentInboxFilter(null, null, "phia", null, null), null, 10);

        assertThat(page.items()).extracting(CommentResponse::commentId)
                .containsExactlyInAnyOrderElementsOf(hits);
    }

    /** That read is of the video, not the owner's partition, so it is the owner's video or nothing. */
    @Test
    void narrowedToSomeoneElsesVideo_theInboxIsRefused() {
        when(videoOwnershipClient.isOwnedBy(300L, OWNER)).thenReturn(false);

        assertThatThrownBy(() -> inbox(new CommentInboxFilter(300L, null, null, null, null)))
                .isInstanceOf(NotVideoOwnerException.class);
    }

    @Test
    void narrowedToOneVideo_theInboxRanksByLikesOrReplies() {
        CommentResponse plain = commentService.addComment(100L, AUTHOR, "bình thường");
        CommentResponse liked = commentService.addComment(100L, 2L, "được thích");
        CommentResponse discussed = commentService.addComment(100L, 3L, "được bàn tán");
        commentService.likeComment(100L, liked.commentId(), 4L);
        commentService.likeComment(100L, liked.commentId(), 5L);
        commentService.addComment(100L, 4L, "đồng ý", discussed.commentId());
        commentService.addComment(100L, 5L, "chuẩn", discussed.commentId());

        assertThat(inbox(ranked(Sort.MOST_LIKED, null, null)))
                .extracting(CommentResponse::commentId)
                .startsWith(liked.commentId());
        assertThat(inbox(ranked(Sort.MOST_REPLIED, null, null)))
                .extracting(CommentResponse::commentId)
                .startsWith(discussed.commentId());
        assertThat(inbox(ranked(Sort.NEWEST, null, null)).getLast().commentId()).isEqualTo(plain.commentId());
    }

    @Test
    void narrowedToOneVideo_theInboxKeepsOnlyAnsweredOrUnansweredThreads() {
        CommentResponse answered = commentService.addComment(100L, AUTHOR, "câu hỏi");
        CommentResponse unanswered = commentService.addComment(100L, 2L, "câu khác");
        commentService.addComment(100L, OWNER, "trả lời đây", answered.commentId());

        assertThat(inbox(ranked(null, true, null)))
                .extracting(CommentResponse::commentId).containsExactly(answered.commentId());
        assertThat(inbox(ranked(null, false, null)))
                .extracting(CommentResponse::commentId).containsExactly(unanswered.commentId());
    }

    @Test
    void narrowedToOneVideo_theInboxKeepsOnlyAuthorsInAFollowerRange() {
        CommentResponse byBig = commentService.addComment(100L, AUTHOR, "từ tài khoản lớn");
        commentService.addComment(100L, 2L, "từ tài khoản nhỏ");
        when(userStatsClient.followerCounts(any())).thenReturn(Map.of(AUTHOR, 50_000L, 2L, 10L));

        assertThat(inbox(ranked(null, null, Set.of(FollowerRange.FROM_10K_TO_100K))))
                .extracting(CommentResponse::commentId).containsExactly(byBig.commentId());
        assertThat(inbox(ranked(null, null, Set.of(FollowerRange.OVER_100K)))).isEmpty();
    }

    @Test
    void aRankedInbox_pagesByOffsetAndRefusesACursorItNeverIssued() {
        for (int i = 0; i < 3; i++) {
            commentService.addComment(100L, AUTHOR, "bình luận " + i);
        }

        CommentPageResponse first = commentService.listForOwner(OWNER, ranked(Sort.MOST_LIKED, null, null), null, 2);
        assertThat(first.items()).hasSize(2);
        assertThat(first.hasMore()).isTrue();
        CommentPageResponse second = commentService.listForOwner(OWNER, ranked(Sort.MOST_LIKED, null, null), first.nextCursor(), 2);
        assertThat(second.items()).hasSize(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();

        assertThatThrownBy(() -> commentService.listForOwner(OWNER, ranked(Sort.MOST_LIKED, null, null), "abc", 2))
                .isInstanceOf(InvalidCommentCursorException.class);
    }

    /**
     * Ranking reads one video's whole thread; across the inbox there is no such read, and the
     * filters used to be dropped in silence — "over 100k followers" answered with every comment.
     */
    @Test
    void aRankedFilterWithoutAVideo_isRefused() {
        for (CommentInboxFilter filter : List.of(
                new CommentInboxFilter(null, null, null, null, null, Sort.MOST_LIKED, null, null),
                new CommentInboxFilter(null, null, null, null, null, null, false, null),
                new CommentInboxFilter(null, null, null, null, null, null, null, Set.of(FollowerRange.OVER_100K)))) {
            assertThatThrownBy(() -> commentService.listForOwner(OWNER, filter, null, 20))
                    .isInstanceOf(InboxFilterNeedsVideoException.class);
        }
    }

    /** Follower counts are asked for as far down the ranked list as the page reaches, not for the whole video. */
    @Test
    void theFollowerFilter_asksOnlyForTheAuthorsThePageNeeds() {
        for (long author = 1_000; author < 1_150; author++) {
            commentService.addComment(100L, author, "bình luận của " + author);
        }
        when(userStatsClient.followerCounts(any())).thenAnswer(call -> {
            java.util.Collection<Long> ids = call.getArgument(0);
            return ids.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> 50_000L));
        });

        CommentPageResponse page = commentService.listForOwner(OWNER,
                ranked(null, null, Set.of(FollowerRange.FROM_10K_TO_100K)), null, 2);

        assertThat(page.items()).hasSize(2);
        assertThat(page.hasMore()).isTrue();
        verify(userStatsClient, times(1)).followerCounts(argThat(ids -> ids.size() <= 100));
    }

    private static CommentInboxFilter ranked(Sort sort, Boolean replied, Set<FollowerRange> followers) {
        return new CommentInboxFilter(100L, null, null, null, null, sort, replied, followers);
    }

    private List<CommentResponse> inbox(CommentInboxFilter filter) {
        return commentService.listForOwner(OWNER, filter, null, 20).items();
    }
}
