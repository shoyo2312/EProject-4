package com.tiktok.userservice.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.request.UpdateProfileRequest;
import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.exception.AlreadyFollowingException;
import com.tiktok.userservice.exception.CannotFollowSelfException;
import com.tiktok.userservice.exception.NotFollowingException;
import com.tiktok.userservice.exception.UserProfileNotFoundException;
import com.tiktok.userservice.service.FollowService;
import com.tiktok.userservice.service.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real Spring Security filter chain (JwtAuthenticationFilter from security-lib)
 * so it verifies token validation, @AuthenticationPrincipal binding and validation error
 * responses end-to-end, not just the controller method in isolation.
 */
@WebMvcTest(controllers = {UserProfileController.class, FollowController.class})
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "app.avatar.allowed-hosts=cdn.example.com"
})
class UserProfileControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserProfileService userProfileService;

    @MockBean
    private FollowService followService;

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(JWT_SECRET);
    }

    private String tokenFor(long userId) {
        // tokenType is mandatory: the filter only accepts access tokens, so a token without it
        // is rejected exactly like a refresh token would be.
        return jwtProvider.generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    @Test
    void getOwnProfile_withoutToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getOwnProfile_withValidToken_bindsCurrentUserIdFromTokenSubject() throws Exception {
        UserProfileResponse response = new UserProfileResponse(42L, "Alice", "bio", null, 3, 5);
        when(userProfileService.getByUserId(42L, 42L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(42L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userId").value(42))
                .andExpect(jsonPath("$.data.displayName").value("Alice"));

        verify(userProfileService).getByUserId(42L, 42L);
    }

    @Test
    void getOwnProfile_withInvalidToken_isRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void updateOwnProfile_withBlankDisplayName_returnsValidationError() throws Exception {
        UpdateProfileRequest invalidRequest = new UpdateProfileRequest("", "bio", null);

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateOwnProfile_withNonHttpsAvatarUrl_returnsValidationError() throws Exception {
        UpdateProfileRequest invalidRequest = new UpdateProfileRequest("Alice", "bio", "javascript:alert(1)");

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateOwnProfile_withAvatarUrlOnDisallowedHost_returnsValidationError() throws Exception {
        UpdateProfileRequest invalidRequest = new UpdateProfileRequest("Alice", "bio", "https://evil-cdn.com/avatar.png");

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void updateOwnProfile_withValidHttpsAvatarUrl_succeeds() throws Exception {
        UpdateProfileRequest validRequest = new UpdateProfileRequest("Alice", "bio", "https://cdn.example.com/avatar.png");
        UserProfileResponse response = new UserProfileResponse(1L, "Alice", "bio", "https://cdn.example.com/avatar.png", 0, 0);
        when(userProfileService.updateOwnProfile(eq(1L), any())).thenReturn(response);

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value("https://cdn.example.com/avatar.png"));
    }

    @Test
    void updateOwnProfile_withBlankAvatarUrl_succeeds() throws Exception {
        UpdateProfileRequest validRequest = new UpdateProfileRequest("Alice", "bio", null);
        UserProfileResponse response = new UserProfileResponse(1L, "Alice", "bio", null, 0, 0);
        when(userProfileService.updateOwnProfile(eq(1L), any())).thenReturn(response);

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk());
    }

    @Test
    void getProfileByUserId_withValidToken_returnsRequestedProfile() throws Exception {
        UserProfileResponse response = new UserProfileResponse(99L, "Bob", null, null, 0, 0);
        when(userProfileService.getByUserId(1L, 99L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/users/99")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(99));
    }

    @Test
    void getProfileByUserId_whenBlocked_returns404() throws Exception {
        when(userProfileService.getByUserId(1L, 99L)).thenThrow(new UserProfileNotFoundException(99L));

        mockMvc.perform(get("/api/v1/users/99")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_NOT_FOUND"));
    }

    @Test
    void listFollowers_whenBlocked_returns404() throws Exception {
        when(followService.listFollowers(eq(1L), eq(2L), any())).thenThrow(new UserProfileNotFoundException(2L));

        mockMvc.perform(get("/api/v1/users/2/followers")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_PROFILE_NOT_FOUND"));
    }

    @Test
    void follow_withValidToken_createsFollowAndReturns201() throws Exception {
        when(followService.follow(1L, 2L)).thenReturn(new FollowResponse(1L, 2L));

        mockMvc.perform(post("/api/v1/users/2/follow")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.followerId").value(1))
                .andExpect(jsonPath("$.data.followingId").value(2));
    }

    @Test
    void follow_self_returnsBadRequestFromDomainException() throws Exception {
        when(followService.follow(1L, 1L)).thenThrow(new CannotFollowSelfException());

        mockMvc.perform(post("/api/v1/users/1/follow")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_FOLLOW_SELF"));
    }

    @Test
    void follow_alreadyFollowing_returnsConflict() throws Exception {
        when(followService.follow(1L, 2L)).thenThrow(new AlreadyFollowingException());

        mockMvc.perform(post("/api/v1/users/2/follow")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_FOLLOWING"));
    }

    @Test
    void follow_withoutToken_isRejectedBeforeReachingService() throws Exception {
        mockMvc.perform(post("/api/v1/users/2/follow"))
                .andExpect(status().isForbidden());

        verify(followService, never()).follow(any(), any());
    }

    @Test
    void unfollow_notFollowing_returnsNotFound() throws Exception {
        doThrow(new NotFollowingException()).when(followService).unfollow(1L, 2L);

        mockMvc.perform(delete("/api/v1/users/2/follow")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOLLOWING"));
    }

    @Test
    void listFollowers_withValidToken_returnsPagedProfiles() throws Exception {
        UserProfileResponse follower = new UserProfileResponse(7L, "Carol", null, null, 1, 2);
        Page<UserProfileResponse> page = new PageImpl<>(List.of(follower), PageRequest.of(0, 10), 1);
        when(followService.listFollowers(eq(1L), eq(2L), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/users/2/followers")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value(7))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }
}
