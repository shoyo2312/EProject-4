package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.BlockClient;
import com.tiktok.interactionservice.client.CommentAudience;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.config.CommentStrikeProperties;
import com.tiktok.interactionservice.dto.response.CommentPermissionResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.exception.CommentRejectedException;
import com.tiktok.interactionservice.exception.CommentingRestrictedException;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Strikes against an account's commenting, against a real Redis: what counts as one, when they
 * restrict, how a restriction is refused and shown, and how an admin lifts it.
 */
@TestPropertySource(properties = {
        "interaction.comment.strikes.threshold=2",
        "interaction.comment.strikes.restrictions=1h,24h"
})
class CommentRestrictionTest extends AbstractInteractionServiceIT {

    private static final Long OWNER = 9L;
    private static final Long AUTHOR = 1L;
    private static final String REFUSED = "địt mẹ mày";

    @Autowired
    private CommentService commentService;

    @Autowired
    private CommentStrikes commentStrikes;

    @Autowired
    private CommentByVideoRepository commentByVideoRepository;

    @Autowired
    private CommentIndexRepository commentIndexRepository;

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
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        when(videoOwnershipClient.commentPolicy(anyLong()))
                .thenReturn(new CommentPolicy(OWNER, false, CommentAudience.EVERYONE));
    }

    private void refuse() {
        assertThatThrownBy(() -> commentService.addComment(100L, AUTHOR, REFUSED))
                .isInstanceOf(CommentRejectedException.class);
    }

    @Test
    void refusedComments_restrictTheAuthor_atTheThreshold_andTheRefusalSaysUntilWhen() {
        refuse();
        assertThatCode(() -> commentService.addComment(100L, AUTHOR, "video hay")).doesNotThrowAnyException();
        refuse();

        assertThatThrownBy(() -> commentService.addComment(101L, AUTHOR, "video hay"))
                .isInstanceOfSatisfying(CommentingRestrictedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("COMMENTING_RESTRICTED");
                    assertThat(e.getRemaining()).isPositive().isLessThanOrEqualTo(Duration.ofHours(1));
                    assertThat(e.getHeaders().getFirst("Retry-After")).isNotNull();
                });
        // Somebody else is not restricted by it.
        assertThatCode(() -> commentService.addComment(101L, 2L, "video hay")).doesNotThrowAnyException();
    }

    /** Held comments are a question for the owner, not a finding against the author. */
    @Test
    void heldComments_areNotStrikes() {
        for (int i = 0; i < 3; i++) {
            CommentResponse held = commentService.addComment(100L + i, AUTHOR, "vào abc.xyz nhận quà nhé");
            assertThat(held.status().name()).isEqualTo("HELD");
        }

        assertThat(commentStrikes.restriction(AUTHOR)).isEmpty();
        assertThat(commentStrikes.standing(AUTHOR).strikes()).isZero();
    }

    @Test
    void thePermission_saysTheCallerIsRestricted_andUntilWhen() {
        refuse();
        refuse();

        CommentPermissionResponse permission = commentService.commentPermission(100L, AUTHOR);

        assertThat(permission.restriction()).isEqualTo(CommentPermissionResponse.Restriction.COMMENTING_RESTRICTED);
        assertThat(permission.restrictedUntil()).isAfter(Instant.now()).isBefore(Instant.now().plus(Duration.ofHours(1)));
        assertThat(commentService.commentPermission(100L, 2L).restriction()).isNull();
        assertThat(commentService.commentPermission(100L, 2L).restrictedUntil()).isNull();
    }

    /** The video's own reasons come first — the comment box is gone for everyone anyway. */
    @Test
    void aVideoWithCommentsOff_saysSo_beforeTheCallersRestriction() {
        refuse();
        refuse();
        when(videoOwnershipClient.commentPolicy(anyLong())).thenReturn(new CommentPolicy(OWNER, true, CommentAudience.EVERYONE));

        assertThat(commentService.commentPermission(100L, AUTHOR).restriction())
                .isEqualTo(CommentPermissionResponse.Restriction.COMMENTS_DISABLED);
    }

    @Test
    void eachRestriction_lastsLongerThanTheOneBefore() {
        refuse();
        refuse();
        assertThat(commentStrikes.restriction(AUTHOR)).get().satisfies(remaining ->
                assertThat(remaining).isLessThanOrEqualTo(Duration.ofHours(1)));

        // Strikes still count while restricted — an admin removing older comments, say.
        commentStrikes.strike(AUTHOR, "test");
        commentStrikes.strike(AUTHOR, "test");

        assertThat(commentStrikes.restriction(AUTHOR)).get().satisfies(remaining ->
                assertThat(remaining).isGreaterThan(Duration.ofHours(1)).isLessThanOrEqualTo(Duration.ofHours(24)));
        assertThat(commentStrikes.standing(AUTHOR).step()).isEqualTo(2);
    }

    @Test
    void anAdmin_canLiftARestriction_andTheStrikesGoWithIt() {
        refuse();
        refuse();

        commentStrikes.lift(AUTHOR, 77L);

        assertThatCode(() -> commentService.addComment(100L, AUTHOR, "video hay")).doesNotThrowAnyException();
        assertThat(commentStrikes.standing(AUTHOR)).isEqualTo(new CommentStrikes.Standing(Duration.ZERO, 0, 0));
    }

    /** Counted by the call that deleted it, so a redelivered event does not strike twice. */
    @Test
    void anAdminRemoval_isAStrike_once() {
        CommentResponse comment = commentService.addComment(100L, AUTHOR, "video hay");

        commentService.removeByAdmin(100L, comment.commentId());
        commentService.removeByAdmin(100L, comment.commentId());

        assertThat(commentStrikes.standing(AUTHOR).strikes()).isEqualTo(1);
        assertThat(commentStrikes.restriction(AUTHOR)).isEmpty();
    }

    @Test
    void switchedOff_noStrikeIsCounted() {
        CommentStrikes off = new CommentStrikes(redisTemplate,
                new CommentStrikeProperties(0, Duration.ofHours(24), List.of(Duration.ofHours(1)), Duration.ofDays(30)));

        assertThat(off.strike(AUTHOR, "test")).isEmpty();
        assertThat(redisTemplate.keys("interaction:comment-*")).isEmpty();
    }
}
