package com.tiktok.videoservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.common.validation.MediaUrlProperties;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.videoservice.config.SecurityConfig;
import com.tiktok.videoservice.dto.response.MusicTrackResponse;
import com.tiktok.videoservice.exception.MusicUnavailableException;
import com.tiktok.videoservice.service.MusicService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = MusicController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@EnableConfigurationProperties(MediaUrlProperties.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"
})
class MusicControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MusicService musicService;

    /** See VideoControllerTest: the JWT filter's revocation check needs it. */
    @MockitoBean
    private StringRedisTemplate revocationRedisTemplate;

    private final JwtProvider jwtProvider = new JwtProvider("test-secret-at-least-32-bytes-long-0123456789");

    private String bearer() {
        return "Bearer " + jwtProvider.generateToken("1",
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    /** Every search spends this service's catalogue quota: not for anonymous callers, GET or not. */
    @Test
    void search_needsASignedInUser() throws Exception {
        // 403, as for every other route here without a token; an expired one is a 401 at the gateway.
        mockMvc.perform(get("/api/v1/videos/music")).andExpect(status().isForbidden());
        verifyNoInteractions(musicService);
    }

    @Test
    void search_answersTheCataloguePage() throws Exception {
        when(musicService.search("chill", 1, 10)).thenReturn(List.of(new MusicTrackResponse("42", "Calm",
                "Someone", 120, "https://stream", "https://img", "http://creativecommons.org/licenses/by/3.0/",
                "https://www.jamendo.com/track/42")));

        mockMvc.perform(get("/api/v1/videos/music").param("q", "chill").param("page", "1").param("size", "10")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("42"))
                .andExpect(jsonPath("$.data[0].artist").value("Someone"));
        verify(musicService).search("chill", 1, 10);
    }

    @Test
    void search_boundsThePageSize() throws Exception {
        mockMvc.perform(get("/api/v1/videos/music").param("size", "500").header("Authorization", bearer()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(musicService);
    }

    @Test
    void search_withTheCatalogueDown_isA503TheAppCanSay() throws Exception {
        when(musicService.search(anyString(), anyInt(), anyInt())).thenThrow(new MusicUnavailableException(null));
        when(musicService.search(null, 0, 20)).thenThrow(new MusicUnavailableException(null));

        mockMvc.perform(get("/api/v1/videos/music").header("Authorization", bearer()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MUSIC_UNAVAILABLE"));
    }
}
