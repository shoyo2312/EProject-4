package com.tiktok.interactionservice.controller;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.client.CommentPolicy;
import com.tiktok.interactionservice.client.VideoOwnershipClient;
import com.tiktok.interactionservice.service.CommentStrikes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a restricted account and the console see over HTTP: the 403 with when it ends, the comment box
 * replaced by the reason, and the admin's view and lift.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = "interaction.comment.strikes.threshold=1")
class CommentRestrictionControllerTest extends AbstractInteractionServiceIT {

    private static final long AUTHOR = 5L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CommentStrikes commentStrikes;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private VideoOwnershipClient videoOwnershipClient;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String bearer(long userId, String role) {
        Map<String, Object> claims = role == null
                ? Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS)
                : Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS, "role", role);
        return "Bearer " + new JwtProvider(jwtSecret).generateToken(String.valueOf(userId), claims, 60_000L);
    }

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        when(videoOwnershipClient.commentPolicy(anyLong())).thenReturn(CommentPolicy.unrestricted());
    }

    @Test
    void aRestrictedAccount_isRefusedWith403_andWhenItEnds() throws Exception {
        commentStrikes.strike(AUTHOR, "test");

        mockMvc.perform(post("/api/v1/interactions/videos/80/comments")
                        .header("Authorization", bearer(AUTHOR, null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"video hay\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("COMMENTING_RESTRICTED"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    void theCommentBox_isReplacedByTheReason() throws Exception {
        commentStrikes.strike(AUTHOR, "test");

        mockMvc.perform(get("/api/v1/interactions/videos/80/comment-permission")
                        .header("Authorization", bearer(AUTHOR, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.restriction").value("COMMENTING_RESTRICTED"))
                .andExpect(jsonPath("$.data.restrictedUntil").exists());
    }

    @Test
    void anAdmin_seesTheRestriction_andLiftsIt() throws Exception {
        commentStrikes.strike(AUTHOR, "test");

        mockMvc.perform(get("/api/v1/interactions/admin/users/{userId}/comment-restriction", AUTHOR)
                        .header("Authorization", bearer(1L, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(AUTHOR))
                .andExpect(jsonPath("$.data.restrictedUntil").exists())
                .andExpect(jsonPath("$.data.step").value(1));

        mockMvc.perform(delete("/api/v1/interactions/admin/users/{userId}/comment-restriction", AUTHOR)
                        .header("Authorization", bearer(1L, "ADMIN")))
                .andExpect(status().isOk());

        assertThat(commentStrikes.restriction(AUTHOR)).isEmpty();
    }

    @Test
    void anyoneElse_cannotSeeOrLiftIt() throws Exception {
        commentStrikes.strike(AUTHOR, "test");

        mockMvc.perform(get("/api/v1/interactions/admin/users/{userId}/comment-restriction", AUTHOR)
                        .header("Authorization", bearer(AUTHOR, "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/interactions/admin/users/{userId}/comment-restriction", AUTHOR)
                        .header("Authorization", bearer(AUTHOR, "USER")))
                .andExpect(status().isForbidden());

        assertThat(commentStrikes.restriction(AUTHOR)).isPresent();
    }
}
