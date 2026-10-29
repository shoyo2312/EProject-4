package com.tiktok.videoservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.videoservice.config.SecurityConfig;
import com.tiktok.videoservice.dto.response.ViewableVideoResponse;
import com.tiktok.videoservice.service.VideoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = InternalViewableVideoController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {"jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"})
class InternalViewableVideoControllerTest {
    @Autowired private MockMvc mvc;
    @Autowired private InternalApiToken token;
    @MockitoBean private VideoService videos;
    @MockitoBean private StringRedisTemplate revocationRedisTemplate;

    @Test
    void onlyInternalTokenCanChooseViewerAndResponseKeepsOrder() throws Exception {
        when(videos.getViewable(7L, List.of("b", "a"))).thenReturn(List.of(
                new ViewableVideoResponse("b", 1L, "thumb-b", "b", 5),
                new ViewableVideoResponse("a", 1L, "thumb-a", "a", 6)));
        mvc.perform(get("/api/v1/videos/internal/viewable").param("viewerId", "7").param("ids", "b,a")
                .header(InternalApiToken.HEADER, token.value()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value("b"))
                .andExpect(jsonPath("$.data[1].id").value("a"));
        verify(videos).getViewable(7L, List.of("b", "a"));
    }

    @Test
    void userJwtAndAnonymousCannotUseInternalEndpoint() throws Exception {
        String jwt = new JwtProvider("test-secret-at-least-32-bytes-long-0123456789").generateToken("7",
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60000L);
        mvc.perform(get("/api/v1/videos/internal/viewable").param("viewerId", "7").param("ids", "a"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/videos/internal/viewable").param("viewerId", "7").param("ids", "a")
                .header("Authorization", "Bearer " + jwt)).andExpect(status().isForbidden());
        verifyNoInteractions(videos);
    }

    @Test
    void cap50IsValidatedBeforeService() throws Exception {
        String ids = String.join(",", java.util.stream.IntStream.range(0, 51).mapToObj(String::valueOf).toList());
        mvc.perform(get("/api/v1/videos/internal/viewable").param("viewerId", "7").param("ids", ids)
                .header(InternalApiToken.HEADER, token.value())).andExpect(status().isBadRequest());
        verifyNoInteractions(videos);
    }
}
