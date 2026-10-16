package com.tiktok.interactionservice.service;

import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.BlockClient;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.entity.CommentStatus;
import com.tiktok.interactionservice.repository.CommentByVideoRepository;
import com.tiktok.interactionservice.repository.CommentIndexRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/** Measuring only: the rules decide, nothing acts on it, every comment posts. */
@TestPropertySource(properties = "moderation.text.enforce=false")
class CommentModerationNotEnforcedTest extends AbstractInteractionServiceIT {

    @Autowired
    private CommentService commentService;

    @Autowired
    private CommentByVideoRepository commentByVideoRepository;

    @Autowired
    private CommentIndexRepository commentIndexRepository;

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
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        when(videoOwnershipClient.commentPolicy(anyLong())).thenReturn(CommentPolicy.unrestricted());
    }

    @Test
    void whatWouldBeHeldOrRefused_posts() {
        assertThat(commentService.addComment(200L, 1L, "vào abc.xyz nhé").status()).isEqualTo(CommentStatus.VISIBLE);
        assertThat(commentService.addComment(200L, 2L, "địt mẹ mày").status()).isEqualTo(CommentStatus.VISIBLE);

        assertThat(commentService.listComments(200L, null, 20).items()).hasSize(2);
    }
}
