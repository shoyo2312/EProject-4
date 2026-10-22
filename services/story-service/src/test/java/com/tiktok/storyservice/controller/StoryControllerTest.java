package com.tiktok.storyservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.storyservice.config.SecurityConfig;
import com.tiktok.storyservice.dto.response.StoryAnnouncementResponse;
import com.tiktok.storyservice.entity.StoryVisibility;
import com.tiktok.storyservice.service.StoryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Through the real security chain: which story endpoints an anonymous caller may reach, and who may
 * reach the internal one.
 */
@WebMvcTest(controllers = {StoryController.class, InternalStoryController.class})
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {
        "jwt.secret=" + StoryControllerTest.JWT_SECRET,
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"
})
class StoryControllerTest {

    static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StoryService storyService;

    @Autowired
    private InternalApiToken internalApiToken;

    private static String bearer(long userId) {
        return "Bearer " + new JwtProvider(JWT_SECRET).generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    @Test
    void anonymousMayReadAProfilesStories() throws Exception {
        when(storyService.listByUser(isNull(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/stories/users/1")).andExpect(status().isOk());

        verify(storyService).listByUser(null, 1L);
    }

    @Test
    void anonymousMayNotReadTheFeed() throws Exception {
        mockMvc.perform(get("/api/v1/stories/feed").param("followedUserIds", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void signedInViewerReadsTheFeedAsThemselves() throws Exception {
        when(storyService.feed(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/stories/feed").param("followedUserIds", "1", "2")
                        .header("Authorization", bearer(9L)))
                .andExpect(status().isOk());

        verify(storyService).feed(9L, List.of(1L, 2L));
    }

    @Test
    void anonymousMayReadRings() throws Exception {
        when(storyService.rings(isNull(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/stories/rings").param("userIds", "1", "2")).andExpect(status().isOk());

        verify(storyService).rings(null, List.of(1L, 2L));
    }

    @Test
    void signedInViewerReadsRingsAsThemselves() throws Exception {
        when(storyService.rings(any(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/stories/rings").param("userIds", "1", "2")
                        .header("Authorization", bearer(9L)))
                .andExpect(status().isOk());

        verify(storyService).rings(9L, List.of(1L, 2L));
    }

    @Test
    void anonymousMayNotChangeVisibility() throws Exception {
        mockMvc.perform(patch("/api/v1/stories/s1/visibility").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"visibility\":\"PRIVATE\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousMayNotListViewers() throws Exception {
        mockMvc.perform(get("/api/v1/stories/s1/viewers")).andExpect(status().isForbidden());
    }

    /**
     * The announcement tells who owns a story and who may see it, whoever asks, so only other
     * services may. Its path falls through to anyRequest().authenticated(), which would admit any
     * signed-in user: a user's JWT must be refused as well as no token, and as a HEAD too, which
     * the GET handler would answer.
     */
    @Test
    void internalAnnouncementAnswersOnlyTheInternalToken() throws Exception {
        String uri = "/api/v1/stories/internal/s1/announcement";
        when(storyService.announcement("s1")).thenReturn(new StoryAnnouncementResponse(7L, StoryVisibility.FRIENDS));

        mockMvc.perform(get(uri)).andExpect(status().isForbidden());
        mockMvc.perform(get(uri).header("Authorization", bearer(9L))).andExpect(status().isForbidden());
        mockMvc.perform(head(uri).header("Authorization", bearer(9L))).andExpect(status().isForbidden());
        verify(storyService, never()).announcement(any());

        mockMvc.perform(get(uri).header(InternalApiToken.HEADER, internalApiToken.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(7))
                .andExpect(jsonPath("$.data.visibility").value("FRIENDS"));
    }
}
