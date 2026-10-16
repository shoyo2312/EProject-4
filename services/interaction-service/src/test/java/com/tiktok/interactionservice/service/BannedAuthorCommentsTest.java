package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.BlockClient;
import com.tiktok.interactionservice.client.CommentAudience;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.UserBan;
import com.tiktok.interactionservice.entity.VideoCounters;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import com.tiktok.interactionservice.repository.HeldCommentRepository;
import com.tiktok.interactionservice.repository.UserBanRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A banned account's comments leave every listing while the ban lasts, and come back when it ends —
 * with nothing to recount, because the counters never moved.
 */
class BannedAuthorCommentsTest extends AbstractInteractionServiceIT {

    private static final Long OWNER = 9L;
    private static final Long BANNED = 1L;
    private static final Long OTHER = 2L;

    @Autowired
    private CommentService commentService;

    @Autowired
    private UserBanRegistry userBanRegistry;

    @Autowired
    private UserBanRepository userBanRepository;

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
        userBanRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        when(videoOwnershipClient.commentPolicy(anyLong()))
                .thenReturn(new CommentPolicy(OWNER, false, CommentAudience.EVERYONE));
        when(videoOwnershipClient.isOwnedBy(anyLong(), eq(OWNER))).thenReturn(true);
    }

    private List<Long> threadIds(Long videoId) {
        return commentService.listComments(videoId, null, 20).items().stream().map(CommentResponse::commentId).toList();
    }

    @Test
    void aBannedAuthorsComments_leaveTheThread_andComeBackWhenTheBanIsLifted() {
        CommentResponse banned = commentService.addComment(100L, BANNED, "video hay");
        CommentResponse other = commentService.addComment(100L, OTHER, "đồng ý");

        userBanRegistry.record(BANNED, UserBan.PERMANENT, Instant.now());
        assertThat(threadIds(100L)).containsExactly(other.commentId());
        // The counter keeps it: lifting the ban must not need a recount.
        assertThat(videoCountersRepository.findById(100L).map(VideoCounters::getCommentCount)).contains(2L);

        userBanRegistry.record(BANNED, null, Instant.now().plusSeconds(1));
        assertThat(threadIds(100L)).containsExactlyInAnyOrder(banned.commentId(), other.commentId());
    }

    @Test
    void aBannedAuthorsReplies_leaveTheReplyThread_too() {
        CommentResponse parent = commentService.addComment(100L, OTHER, "video hay");
        commentService.addComment(100L, BANNED, "đúng vậy", parent.commentId());
        CommentResponse kept = commentService.addComment(100L, OTHER, "cảm ơn", parent.commentId());

        userBanRegistry.record(BANNED, UserBan.PERMANENT, Instant.now());

        assertThat(commentService.listReplies(100L, parent.commentId(), null, 20).items())
                .extracting(CommentResponse::commentId).containsExactly(kept.commentId());
    }

    @Test
    void aBannedAuthorsHeldComments_leaveTheOwnersQueue() {
        commentService.addComment(100L, BANNED, "vào abc.xyz nhận quà nhé");

        userBanRegistry.record(BANNED, UserBan.PERMANENT, Instant.now());

        assertThat(commentService.listHeld(100L, null, 20, OWNER).items()).isEmpty();
    }

    /** auth-service's sweep lifts a temporary ban without an event; the date alone has to end it. */
    @Test
    void aTemporaryBanThatHasRunOut_hidesNothing() {
        CommentResponse comment = commentService.addComment(100L, BANNED, "video hay");

        userBanRegistry.record(BANNED, Instant.now().minus(Duration.ofMinutes(1)), Instant.now().minus(Duration.ofDays(3)));

        assertThat(threadIds(100L)).containsExactly(comment.commentId());
    }

    /** A replay off the DLT of an older decision must not undo a newer one. */
    @Test
    void aLateOlderDecision_changesNothing() {
        commentService.addComment(100L, BANNED, "video hay");
        Instant decided = Instant.now();

        userBanRegistry.record(BANNED, UserBan.PERMANENT, decided);
        userBanRegistry.record(BANNED, null, decided.minus(Duration.ofHours(1)));

        assertThat(threadIds(100L)).isEmpty();
        assertThat(userBanRepository.findById(BANNED)).get()
                .satisfies(ban -> assertThat(ban.getBannedUntil()).isEqualTo(UserBan.PERMANENT));
    }

    /** A redelivery repeats the same decision — applied, and the same as before. */
    @Test
    void theSameDecisionTwice_isTheSameDecision() {
        Instant decided = Instant.now();

        userBanRegistry.record(BANNED, UserBan.PERMANENT, decided);
        userBanRegistry.record(BANNED, UserBan.PERMANENT, decided);

        assertThat(userBanRegistry.bannedAmong(List.of(BANNED, OTHER))).containsExactly(BANNED);
    }
}
