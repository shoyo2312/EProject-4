package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.BlockClient;
import com.tiktok.interactionservice.client.CommentAudience;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentByVideoKey;
import com.tiktok.interactionservice.entity.CommentStatus;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import com.tiktok.interactionservice.repository.HeldCommentRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * A video owner's own comment filters, end to end: from the policy video-service relays to a comment
 * held in their queue — and the flags a comment that was posted anyway keeps for the admin list.
 */
class CreatorCommentFilterTest extends AbstractInteractionServiceIT {

    private static final Long OWNER = 9L;
    private static final Long AUTHOR = 1L;

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
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        when(videoOwnershipClient.isOwnedBy(anyLong(), eq(OWNER))).thenReturn(true);
    }

    private void ownerFilters(boolean spamOffensive, boolean all, List<String> keywords) {
        when(videoOwnershipClient.commentPolicy(anyLong()))
                .thenReturn(new CommentPolicy(OWNER, false, CommentAudience.EVERYONE, spamOffensive, all, keywords));
    }

    @Test
    void holdEverything_putsEveryCommentInTheOwnersQueue_butNotTheOwnersOwn() {
        ownerFilters(true, true, List.of());

        CommentResponse held = commentService.addComment(100L, AUTHOR, "video hay quá");
        CommentResponse own = commentService.addComment(100L, OWNER, "cảm ơn mọi người");

        assertThat(held.status()).isEqualTo(CommentStatus.HELD);
        assertThat(own.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(commentService.listHeld(100L, null, 20, OWNER).items())
                .extracting(CommentResponse::commentId).containsExactly(held.commentId());
        assertThat(commentService.listComments(100L, null, 20).items())
                .extracting(CommentResponse::commentId).containsExactly(own.commentId());
        assertThat(commentByVideoRepository.findById(key(100L, held.commentId()))).get()
                .satisfies(row -> assertThat(row.getModReason()).isEqualTo("CREATOR_FILTER:all"));
    }

    @Test
    void aKeyword_holdsTheCommentsThatContainIt() {
        ownerFilters(true, false, List.of("spoiler"));

        assertThat(commentService.addComment(100L, AUTHOR, "Spoiler: cuối phim nam chính chết").status())
                .isEqualTo(CommentStatus.HELD);
        assertThat(commentService.addComment(100L, AUTHOR, "spoilers là gì vậy").status())
                .as("whole syllables only").isEqualTo(CommentStatus.VISIBLE);
    }

    /**
     * Waived by the owner, so posted — and the flag stays on the row: the admin list shows what was
     * let through and why.
     */
    @Test
    void aWaivedLink_isPosted_withItsFlagKept() {
        ownerFilters(false, false, List.of());

        CommentResponse posted = commentService.addComment(100L, AUTHOR, "xem thêm ở abc.xyz nhé");

        assertThat(posted.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(commentService.listComments(100L, null, 20).items()).hasSize(1);
        assertThat(commentByVideoRepository.findById(key(100L, posted.commentId()))).get()
                .satisfies(row -> {
                    assertThat(row.getModReason()).isEqualTo("SPAM:link:abc.xyz");
                    assertThat(row.getModVersion()).startsWith("rules-");
                });
    }

    @Test
    void aCleanComment_storesNoReason() {
        ownerFilters(true, false, List.of());

        CommentResponse posted = commentService.addComment(100L, AUTHOR, "video hay quá");

        assertThat(commentByVideoRepository.findById(key(100L, posted.commentId()))).get()
                .satisfies(row -> {
                    assertThat(row.getModReason()).isNull();
                    assertThat(row.getModVersion()).isNull();
                });
    }

    private static CommentByVideoKey key(Long videoId, Long commentId) {
        return CommentByVideoKey.builder().videoId(videoId).commentId(commentId).build();
    }
}
