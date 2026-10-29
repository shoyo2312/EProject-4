package com.tiktok.interactionservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.interactionservice.config.SecurityConfig;
import com.tiktok.interactionservice.service.ChatShareService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InternalChatShareController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {"jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"})
class InternalChatShareControllerTest {
    @Autowired private MockMvc mvc;
    @Autowired private InternalApiToken token;
    @MockitoBean private ChatShareService shares;
    @MockitoBean private StringRedisTemplate revocationRedisTemplate;
    private static final String BODY = "{\"userId\":7,\"dedupeKey\":\"message\"}";

    @Test
    void internalPostReturnsCountedThenDuplicate() throws Exception {
        when(shares.count(123L, 7L, "message")).thenReturn(true, false);
        mvc.perform(post("/api/v1/interactions/internal/videos/123/shares").contentType(MediaType.APPLICATION_JSON)
                .content(BODY).header(InternalApiToken.HEADER, token.value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.counted").value(true));
        mvc.perform(post("/api/v1/interactions/internal/videos/123/shares").contentType(MediaType.APPLICATION_JSON)
                .content(BODY).header(InternalApiToken.HEADER, token.value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.counted").value(false));
    }

    @Test
    void userJwtAndAnonymousCannotForgeShares() throws Exception {
        String jwt = new JwtProvider("test-secret-at-least-32-bytes-long-0123456789").generateToken("7",
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60000L);
        mvc.perform(post("/api/v1/interactions/internal/videos/123/shares").contentType(MediaType.APPLICATION_JSON)
                .content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/interactions/internal/videos/123/shares").contentType(MediaType.APPLICATION_JSON)
                .content(BODY).header("Authorization", "Bearer " + jwt)).andExpect(status().isForbidden());
        verifyNoInteractions(shares);
    }

    @Test
    void dedupeKeyIsRequired() throws Exception {
        mvc.perform(post("/api/v1/interactions/internal/videos/123/shares").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":7}").header(InternalApiToken.HEADER, token.value()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(shares);
    }
}
