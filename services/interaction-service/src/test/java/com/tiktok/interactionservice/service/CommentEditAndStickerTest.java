package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.BlockClient;
import com.tiktok.interactionservice.client.CommentAudience;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.FriendshipClient;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.entity.CommentStatus;
import com.tiktok.interactionservice.exception.CommentEditNeedsReviewException;
import com.tiktok.interactionservice.exception.CommentNotFoundException;
import com.tiktok.interactionservice.exception.CommentRejectedException;
import com.tiktok.interactionservice.exception.InvalidStickerException;
import com.tiktok.interactionservice.exception.NotCommentOwnerException;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** Stickers on comments, and the author editing their own text. */
class CommentEditAndStickerTest extends AbstractInteractionServiceIT {

    /** In the pack chat-service ships, which this service reads at build time. */
    private static final String STICKER = "grinning-face";

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

    @MockitoBean
    private VideoOwnershipClient videoOwnershipClient;

    @MockitoBean
    private FriendshipClient friendshipClient;

    @MockitoBean
    private BlockClient blockClient;

    @BeforeEach
    void cleanUp() {
        commentByVideoRepository.deleteAll();
        commentIndexRepository.deleteAll();
        videoCountersRepository.deleteAll();
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        when(videoOwnershipClient.commentPolicy(anyLong())).thenReturn(CommentPolicy.unrestricted());
    }

    @Test
    void aStickerOnItsOwn_isAComment_withEmptyTextAndADrawableSticker() {
        CommentResponse posted = commentService.addComment(300L, 1L, null, null, STICKER);

        assertThat(posted.content()).isEmpty();
        assertThat(posted.status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(posted.sticker().stickerId()).isEqualTo(STICKER);
        assertThat(posted.sticker().url()).endsWith("/stickers/fluent-3d/v1/grinning-face.png");
        assertThat(posted.sticker().width()).isPositive();

        // Listed like any comment, sticker and all.
        assertThat(commentService.listComments(300L, null, 20).items())
                .singleElement()
                .satisfies(listed -> assertThat(listed.sticker().stickerId()).isEqualTo(STICKER));
    }

    @Test
    void aStickerWithText_keepsBoth() {
        CommentResponse posted = commentService.addComment(301L, 1L, "haha", null, STICKER);

        assertThat(posted.content()).isEqualTo("haha");
        assertThat(posted.sticker().stickerId()).isEqualTo(STICKER);
    }

    @Test
    void aStickerAsAReply_landsInTheThread() {
        CommentResponse top = commentService.addComment(302L, 1L, "top");

        CommentResponse reply = commentService.addComment(302L, 2L, "", top.commentId(), STICKER);

        assertThat(reply.parentId()).isEqualTo(top.commentId());
        assertThat(commentService.listReplies(302L, top.commentId(), null, 20).items())
                .singleElement()
                .satisfies(listed -> assertThat(listed.sticker()).isNotNull());
    }

    @Test
    void anUnknownSticker_isRefusedAndNothingIsStored() {
        assertThatThrownBy(() -> commentService.addComment(303L, 1L, "hi", null, "no-such-sticker"))
                .isInstanceOf(InvalidStickerException.class);

        assertThat(commentService.listComments(303L, null, 20).items()).isEmpty();
    }

    @Test
    void theOwnersHoldEverything_holdsAStickerToo() {
        when(videoOwnershipClient.commentPolicy(304L)).thenReturn(
                new CommentPolicy(9L, false, CommentAudience.EVERYONE, true, true, List.of()));

        CommentResponse posted = commentService.addComment(304L, 1L, null, null, STICKER);

        assertThat(posted.status()).isEqualTo(CommentStatus.HELD);
    }

    @Test
    void theAuthorEditsTheText_andTheEditShowsInTheListing() {
        CommentResponse posted = commentService.addComment(310L, 1L, "helo");
        assertThat(posted.editedAt()).isNull();

        CommentResponse edited = commentService.editComment(310L, posted.commentId(), 1L, "hello");

        assertThat(edited.content()).isEqualTo("hello");
        assertThat(edited.editedAt()).isNotNull();
        assertThat(edited.commentId()).isEqualTo(posted.commentId());
        assertThat(commentService.listComments(310L, null, 20).items())
                .singleElement()
                .satisfies(listed -> {
                    assertThat(listed.content()).isEqualTo("hello");
                    assertThat(listed.editedAt()).isNotNull();
                });
    }

    @Test
    void anEdit_keepsTheLikesAndTheSticker() {
        CommentResponse posted = commentService.addComment(311L, 1L, "nice", null, STICKER);
        commentService.likeComment(311L, posted.commentId(), 2L);

        CommentResponse edited = commentService.editComment(311L, posted.commentId(), 1L, "very nice");

        assertThat(edited.likeCount()).isEqualTo(1);
        assertThat(edited.sticker().stickerId()).isEqualTo(STICKER);
    }

    @Test
    void theSameTextAgain_isNotAnEdit() {
        CommentResponse posted = commentService.addComment(312L, 1L, "same");

        CommentResponse unchanged = commentService.editComment(312L, posted.commentId(), 1L, "same");

        assertThat(unchanged.editedAt()).isNull();
    }

    @Test
    void nobodyButTheAuthor_mayEdit_notEvenTheVideosOwner() {
        when(videoOwnershipClient.commentPolicy(313L)).thenReturn(
                new CommentPolicy(9L, false, CommentAudience.EVERYONE));
        when(videoOwnershipClient.isOwnedBy(313L, 9L)).thenReturn(true);
        CommentResponse posted = commentService.addComment(313L, 1L, "mine");

        assertThatThrownBy(() -> commentService.editComment(313L, posted.commentId(), 9L, "theirs"))
                .isInstanceOf(NotCommentOwnerException.class);
        assertThatThrownBy(() -> commentService.editComment(313L, posted.commentId(), 2L, "theirs"))
                .isInstanceOf(NotCommentOwnerException.class);
    }

    @Test
    void aDeletedComment_cannotBeEdited() {
        CommentResponse posted = commentService.addComment(314L, 1L, "gone soon");
        commentService.deleteComment(314L, posted.commentId(), 1L);

        assertThatThrownBy(() -> commentService.editComment(314L, posted.commentId(), 1L, "back"))
                .isInstanceOf(CommentNotFoundException.class);
    }

    @Test
    void anEditTheRulesRefuse_isRefused_andTheTextStays() {
        CommentResponse posted = commentService.addComment(315L, 1L, "video hay quá");

        assertThatThrownBy(() -> commentService.editComment(315L, posted.commentId(), 1L, "địt mẹ mày"))
                .isInstanceOf(CommentRejectedException.class);

        assertThat(commentService.listComments(315L, null, 20).items())
                .singleElement()
                .extracting(CommentResponse::content)
                .isEqualTo("video hay quá");
    }

    @Test
    void anEditThatWouldBeHeld_isNotSaved() {
        CommentResponse posted = commentService.addComment(316L, 1L, "video hay quá");

        assertThatThrownBy(() -> commentService.editComment(316L, posted.commentId(), 1L, "xem tại abc.xyz"))
                .isInstanceOf(CommentEditNeedsReviewException.class);

        assertThat(commentService.listComments(316L, null, 20).items())
                .singleElement()
                .satisfies(listed -> {
                    assertThat(listed.content()).isEqualTo("video hay quá");
                    assertThat(listed.editedAt()).isNull();
                });
    }
}
