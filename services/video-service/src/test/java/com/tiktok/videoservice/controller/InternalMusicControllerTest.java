package com.tiktok.videoservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.common.validation.MediaUrlProperties;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.videoservice.client.MusicTrack;
import com.tiktok.videoservice.config.SecurityConfig;
import com.tiktok.videoservice.exception.MusicNotFoundException;
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

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InternalMusicController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@EnableConfigurationProperties(MediaUrlProperties.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "INTERNAL_API_TOKEN=" + InternalMusicControllerTest.TOKEN
})
class InternalMusicControllerTest {

    static final String TOKEN = "internal-token-for-tests-0123456789abcdef";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MusicService musicService;

    /** See VideoControllerTest: the JWT filter's revocation check needs it. */
    @MockitoBean
    private StringRedisTemplate revocationRedisTemplate;

    /** The answer carries the download URL, which only other services are given. */
    @Test
    void track_needsTheInternalToken() throws Exception {
        mockMvc.perform(get("/api/v1/videos/internal/music/42")).andExpect(status().isForbidden());
        verifyNoInteractions(musicService);
    }

    @Test
    void track_answersTheCatalogueEntryWithItsDownload() throws Exception {
        when(musicService.requireTrack("42")).thenReturn(new MusicTrack("42", "Calm", "Someone", 120,
                "https://stream", "https://prod-1.storage.jamendo.com/download/42", null,
                "http://creativecommons.org/licenses/by/3.0/", "https://www.jamendo.com/track/42"));

        mockMvc.perform(get("/api/v1/videos/internal/music/42").header(InternalApiToken.HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Calm"))
                .andExpect(jsonPath("$.data.downloadUrl").value("https://prod-1.storage.jamendo.com/download/42"));
    }

    @Test
    void track_unusable_isMusicNotFound() throws Exception {
        when(musicService.requireTrack("7")).thenThrow(new MusicNotFoundException());

        mockMvc.perform(get("/api/v1/videos/internal/music/7").header(InternalApiToken.HEADER, TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MUSIC_NOT_FOUND"));
    }
}
