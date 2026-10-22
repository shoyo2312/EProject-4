package com.tiktok.interactionservice.controller;

import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.dto.response.CommentResponse;
import com.tiktok.interactionservice.service.AdminCommentDirectory;
import com.tiktok.interactionservice.service.CommentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The internal prefix is called by services with no user behind them — notification-service, off a
 * Kafka event — so it has to answer without a user's token. api-gateway keeps it off the internet,
 * and the internal token keeps out whatever reaches this port without the gateway.
 */
@AutoConfigureMockMvc
class InternalCommentControllerTest extends AbstractInteractionServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InternalApiToken internalApiToken;

    @Autowired
    private CommentService commentService;

    // The comment is seeded through addComment, whose guards ask video-service.
    @MockitoBean
    private VideoOwnershipClient videoOwnershipClient;

    @Autowired
    private StringRedisTemplate redisTemplate;

    // The handler's collaborator, spied so a test can tell whether a request ever reached it.
    @MockitoSpyBean
    private AdminCommentDirectory adminCommentDirectory;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String tokenFor(long userId) {
        return new JwtProvider(jwtSecret).generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    @BeforeEach
    void permissivePolicy() {
        when(videoOwnershipClient.commentPolicy(anyLong())).thenReturn(CommentPolicy.unrestricted());
        // Two tests seed the same comment; the duplicate guard remembers the first in Redis.
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void author_answersWithoutAToken() throws Exception {
        CommentResponse comment = commentService.addComment(80L, 9L, "reported");

        mockMvc.perform(get("/api/v1/interactions/internal/videos/{videoId}/comments/{commentId}/author",
                        80L, comment.commentId())
                        .header(InternalApiToken.HEADER, internalApiToken.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(9))
                .andExpect(jsonPath("$.data.commentId").value(comment.commentId()));
    }

    @Test
    void author_ofAnUnknownComment_isNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/interactions/internal/videos/{videoId}/comments/{commentId}/author",
                        80L, 424242L)
                        .header(InternalApiToken.HEADER, internalApiToken.value()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("COMMENT_NOT_FOUND"));
    }

    @Test
    void author_refusesACallerWithoutTheInternalToken() throws Exception {
        CommentResponse comment = commentService.addComment(80L, 9L, "reported");

        mockMvc.perform(get("/api/v1/interactions/internal/videos/{videoId}/comments/{commentId}/author",
                        80L, comment.commentId()))
                .andExpect(status().isForbidden());
    }

    /**
     * Spring MVC answers a HEAD with the GET handler, so an internal rule for GET alone lets a HEAD
     * fall through to anyRequest().authenticated(): a user's JWT would ask who wrote a comment.
     */
    @Test
    void author_refusesAHeadWithAUsersJwt() throws Exception {
        CommentResponse comment = commentService.addComment(80L, 9L, "reported");

        mockMvc.perform(head("/api/v1/interactions/internal/videos/{videoId}/comments/{commentId}/author",
                        80L, comment.commentId())
                        .header("Authorization", "Bearer " + tokenFor(9L)))
                .andExpect(status().isForbidden());

        verify(adminCommentDirectory, never()).authorOf(any(), any());
    }
}
