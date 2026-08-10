package com.tiktok.videoservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.common.validation.MediaUrlProperties;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.videoservice.config.SecurityConfig;
import com.tiktok.videoservice.dto.request.CreateVideoRequest;
import com.tiktok.videoservice.dto.response.VideoResponse;
import com.tiktok.videoservice.entity.VideoStatus;
import com.tiktok.videoservice.entity.VideoVisibility;
import com.tiktok.videoservice.exception.NotVideoOwnerException;
import com.tiktok.videoservice.exception.VideoNotFoundException;
import com.tiktok.videoservice.service.VideoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real Spring Security filter chain (JwtAuthenticationFilter from security-lib)
 * so it verifies token validation, @AuthenticationPrincipal binding and error responses
 * end-to-end, not just the controller method in isolation. Mirrors user-service's
 * UserProfileControllerTest.
 */
@WebMvcTest(controllers = VideoController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class})
@EnableConfigurationProperties(MediaUrlProperties.class)
@TestPropertySource(properties = {
        "jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "app.media.allowed-hosts=cdn.example.com",
        "app.media.allowed-buckets=video-media"
})
class VideoControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private VideoService videoService;

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(JWT_SECRET);
    }

    private String tokenFor(long userId) {
        return jwtProvider.generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    @Test
    void publish_withoutToken_isRejected() throws Exception {
        CreateVideoRequest request = new CreateVideoRequest("title", "desc", "s3://video-media/raw/1.mp4", VideoVisibility.PUBLIC);

        mockMvc.perform(post("/api/v1/videos")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());

        verify(videoService, never()).publish(any(), any());
    }

    @Test
    void publish_withValidToken_createsVideo() throws Exception {
        CreateVideoRequest request = new CreateVideoRequest("title", "desc", "s3://video-media/raw/1.mp4", VideoVisibility.PUBLIC);
        VideoResponse response = new VideoResponse("v1", 42L, "title", "desc", null, null, null,
                VideoStatus.PROCESSING, VideoVisibility.PUBLIC, 0, 0, 0, Instant.now());
        when(videoService.publish(eq(42L), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/videos")
                        .header("Authorization", "Bearer " + tokenFor(42L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("v1"))
                .andExpect(jsonPath("$.data.status").value("PROCESSING"));

        verify(videoService).publish(42L, request);
    }

    @Test
    void publish_withBlankTitle_returnsValidationError() throws Exception {
        CreateVideoRequest invalidRequest = new CreateVideoRequest("", "desc", "s3://video-media/raw/1.mp4", VideoVisibility.PUBLIC);

        mockMvc.perform(post("/api/v1/videos")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(videoService, never()).publish(any(), any());
    }

    /**
     * rawFileUrl reaches media-worker, which fetches it. A URL the client chose on a host we do
     * not own would turn our own backend into the requester — so it has to be refused at the
     * edge, not merely stored.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example.net/payload.mp4",
            "s3://someone-elses-bucket/1.mp4",
            "javascript:alert(1)",
            "file:///etc/passwd"
    })
    void publish_rawFileUrlOffOurStorage_returnsValidationError(String rawFileUrl) throws Exception {
        CreateVideoRequest request = new CreateVideoRequest("title", "desc", rawFileUrl, VideoVisibility.PUBLIC);

        mockMvc.perform(post("/api/v1/videos")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(videoService, never()).publish(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"s3://video-media/raw/1.mp4", "https://cdn.example.com/raw/1.mp4"})
    void publish_rawFileUrlOnOurStorage_isAccepted(String rawFileUrl) throws Exception {
        CreateVideoRequest request = new CreateVideoRequest("title", "desc", rawFileUrl, VideoVisibility.PUBLIC);
        VideoResponse response = new VideoResponse("v1", 1L, "title", "desc", null, null, null,
                VideoStatus.PROCESSING, VideoVisibility.PUBLIC, 0, 0, 0, Instant.now());
        when(videoService.publish(eq(1L), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/videos")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void getFeed_withoutToken_isAllowed() throws Exception {
        when(videoService.getFeed(any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/videos/feed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void listByUser_withoutToken_passesNullRequester() throws Exception {
        when(videoService.listByUser(isNull(), eq(7L), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/videos/users/7"))
                .andExpect(status().isOk());

        // Null requester is what makes the service take the published+public branch. If the
        // controller ever stopped forwarding the principal, this endpoint would silently start
        // serving whatever branch the service treats as "owner".
        verify(videoService).listByUser(isNull(), eq(7L), any());
    }

    @Test
    void listByUser_withToken_passesAuthenticatedRequester() throws Exception {
        when(videoService.listByUser(eq(7L), eq(7L), any()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mockMvc.perform(get("/api/v1/videos/users/7")
                        .header("Authorization", "Bearer " + tokenFor(7L)))
                .andExpect(status().isOk());

        verify(videoService).listByUser(eq(7L), eq(7L), any());
    }

    @Test
    void getById_unknownVideo_returnsNotFound() throws Exception {
        when(videoService.getById(1L, "missing")).thenThrow(new VideoNotFoundException("missing"));

        mockMvc.perform(get("/api/v1/videos/missing")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
    }

    @Test
    void delete_notOwner_returnsForbidden() throws Exception {
        doThrow(new NotVideoOwnerException("v1")).when(videoService).delete(2L, "v1");

        mockMvc.perform(delete("/api/v1/videos/v1")
                        .header("Authorization", "Bearer " + tokenFor(2L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_VIDEO_OWNER"));
    }

    @Test
    void delete_owner_returnsSuccess() throws Exception {
        mockMvc.perform(delete("/api/v1/videos/v1")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(videoService).delete(1L, "v1");
    }

    /**
     * Regression for the advice swallowing Spring MVC's own request exceptions. Until
     * BaseExceptionHandler extended ResponseEntityExceptionHandler, the {@code Exception.class}
     * handler was the only match for all three of these, so a plain client mistake answered
     * 500 INTERNAL_ERROR and logged a stack trace.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            // unknown enum constant — HttpMessageNotReadableException
            "{\"title\":\"t\",\"rawFileUrl\":\"s3://video-media/raw/1.mp4\",\"visibility\":\"BOGUS\"}",
            // truncated body — HttpMessageNotReadableException
            "{\"title\":\"t\","
    })
    void publish_withUnreadableBody_returnsBadRequest(String body) throws Exception {
        mockMvc.perform(post("/api/v1/videos")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verify(videoService, never()).publish(any(), any());
    }

    @Test
    void listByUser_withNonNumericUserId_returnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/videos/users/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));

        verify(videoService, never()).listByUser(any(), any(), any());
    }

    @Test
    void publish_withUnsupportedMethod_returnsMethodNotAllowed() throws Exception {
        mockMvc.perform(put("/api/v1/videos")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
