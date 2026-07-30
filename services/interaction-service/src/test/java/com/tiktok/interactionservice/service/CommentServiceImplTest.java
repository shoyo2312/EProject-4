package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.CommentPageResponse;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.exception.CommentNotFoundException;
import com.tiktok.interactionservice.exception.NotCommentOwnerException;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.VideoCountersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommentServiceImplTest extends AbstractInteractionServiceIT {

    @Autowired
    private CommentService commentService;

    @Autowired
    private CommentByVideoRepository commentByVideoRepository;

    @Autowired
    private VideoCountersRepository videoCountersRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

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

    @Test
    void deleteComment_unknownComment_throwsNotFound() {
        assertThatThrownBy(() -> commentService.deleteComment(25L, 999L, 1L))
                .isInstanceOf(CommentNotFoundException.class);
    }
}
