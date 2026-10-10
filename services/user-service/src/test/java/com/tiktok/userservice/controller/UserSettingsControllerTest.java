package com.tiktok.userservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.SettingsAudience;
import com.tiktok.userservice.service.FollowService;
import com.tiktok.userservice.service.UserSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {UserSettingsController.class, InternalFollowController.class, FollowController.class})
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"
})
class UserSettingsControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    private static final UserSettingsResponse DEFAULTS = new UserSettingsResponse(
            SettingsAudience.EVERYONE, SettingsAudience.EVERYONE, null, null, null, true, false);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InternalApiToken internalApiToken;

    @MockitoBean
    private UserSettingsService userSettingsService;

    @MockitoBean
    private FollowService followService;

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
    void getOwnSettings_answersTheFlatKeyedObject() throws Exception {
        when(userSettingsService.getOwnSettings(1L)).thenReturn(new UserSettingsResponse(
                SettingsAudience.FRIENDS, SettingsAudience.NO_ONE, 60,
                LocalTime.of(22, 30), LocalTime.of(7, 0), false, false));

        mockMvc.perform(get("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentAudience").value("FRIENDS"))
                .andExpect(jsonPath("$.data.dmAudience").value("NO_ONE"))
                .andExpect(jsonPath("$.data.screenTimeLimitMinutes").value(60))
                // Minute precision both ways, so a client can PATCH back what it read.
                .andExpect(jsonPath("$.data.sleepHoursStart").value("22:30"))
                .andExpect(jsonPath("$.data.sleepHoursEnd").value("07:00"))
                .andExpect(jsonPath("$.data.weeklyScreenTimeDigest").value(false));
    }

    /**
     * The tri-state the whole DTO exists for: {@code dmAudience} is absent and
     * {@code screenTimeLimitMinutes} is explicitly null, and the service has to be able to tell
     * them apart.
     */
    @Test
    void patch_distinguishesAnAbsentKeyFromAnExplicitNull() throws Exception {
        when(userSettingsService.updateOwnSettings(eq(1L), any())).thenReturn(DEFAULTS);

        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commentAudience": "FRIENDS", "screenTimeLimitMinutes": null}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateUserSettingsRequest> captor =
                ArgumentCaptor.forClass(UpdateUserSettingsRequest.class);
        verify(userSettingsService).updateOwnSettings(eq(1L), captor.capture());

        UpdateUserSettingsRequest request = captor.getValue();
        assertThat(request.isCommentAudienceSet()).isTrue();
        assertThat(request.getCommentAudience()).isEqualTo("FRIENDS");
        assertThat(request.isScreenTimeLimitMinutesSet()).isTrue();
        assertThat(request.getScreenTimeLimitMinutes()).isNull();
        assertThat(request.isDmAudienceSet()).isFalse();
    }

    @Test
    void patch_unknownAudience_isAValidationError() throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"commentAudience": "MUTUALS"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(userSettingsService, never()).updateOwnSettings(any(), any());
    }

    @Test
    void patch_screenTimeLimit_isBoundedAtTenMinutesAndOneDay() throws Exception {
        when(userSettingsService.updateOwnSettings(eq(1L), any())).thenReturn(DEFAULTS);

        assertLimitAccepted(10);
        assertLimitAccepted(1440);
        assertLimitRejected(9);
        assertLimitRejected(1441);
    }

    private void assertLimitAccepted(int minutes) throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"screenTimeLimitMinutes\": " + minutes + "}"))
                .andExpect(status().isOk());
    }

    private void assertLimitRejected(int minutes) throws Exception {
        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"screenTimeLimitMinutes\": " + minutes + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void internalSettings_answerWithoutAToken() throws Exception {
        when(userSettingsService.getSettingsPolicies(List.of(7L))).thenReturn(List.of(
                new UserSettingsPolicyResponse(7L, SettingsAudience.NO_ONE, SettingsAudience.FRIENDS)));

        mockMvc.perform(get("/api/v1/users/internal/settings").param("ids", "7")
                        .header(InternalApiToken.HEADER, internalApiToken.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(7))
                .andExpect(jsonPath("$.data[0].commentAudience").value("NO_ONE"))
                .andExpect(jsonPath("$.data[0].dmAudience").value("FRIENDS"));
    }

    /**
     * The one that would break silently: {@code /users/internal/friendship} also matches
     * {@code /users/{userId}/friendship}, which requires a JWT. A literal segment outranks a path
     * variable, so the tokenless mapping wins — if that ever stopped being true, chat-service
     * would start getting 401s instead of answers.
     */
    @Test
    void internalFriendship_answersWithoutAToken_andIsNotShadowedByThePathVariable() throws Exception {
        when(followService.friendship(1L, 2L)).thenReturn(new FriendshipResponse(2L, true, true, false, false));

        mockMvc.perform(get("/api/v1/users/internal/friendship")
                        .header(InternalApiToken.HEADER, internalApiToken.value())
                        .param("userA", "1")
                        .param("userB", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.friends").value(true));

        // The authenticated sibling still refuses the same anonymous caller. Which 4xx it is
        // depends on the entry point this slice happens to build, and is not the point here.
        mockMvc.perform(get("/api/v1/users/5/friendship"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void patch_privateAccount_reachesTheServiceAndComesBack() throws Exception {
        when(userSettingsService.updateOwnSettings(eq(1L), any())).thenReturn(new UserSettingsResponse(
                SettingsAudience.EVERYONE, SettingsAudience.EVERYONE, null, null, null, true, true));

        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"privateAccount": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.privateAccount").value(true));

        ArgumentCaptor<UpdateUserSettingsRequest> captor =
                ArgumentCaptor.forClass(UpdateUserSettingsRequest.class);
        verify(userSettingsService).updateOwnSettings(eq(1L), captor.capture());
        assertThat(captor.getValue().isPrivateAccountSet()).isTrue();
        assertThat(captor.getValue().getPrivateAccount()).isTrue();
    }

    /** interaction-service asks for anonymous callers of a public tab, so the viewer is optional. */
    @Test
    void internalFriendship_withoutAViewer_isTheAnonymousQuestion() throws Exception {
        when(followService.friendship(null, 2L)).thenReturn(new FriendshipResponse(2L, false, false, false, true));

        mockMvc.perform(get("/api/v1/users/internal/friendship").param("userB", "2")
                        .header(InternalApiToken.HEADER, internalApiToken.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.privateAccount").value(true));
    }

    /**
     * Same shadowing risk as the friendship check, and a worse one: {@code /users/{userId}/following}
     * is the following list, which needs a JWT. The literal segment must keep winning.
     */
    @Test
    void internalFollowing_answersWithoutAToken_andIsNotShadowedByTheFollowingList() throws Exception {
        when(followService.followingAmong(1L, List.of(7L, 8L))).thenReturn(List.of(8L));

        mockMvc.perform(get("/api/v1/users/internal/following")
                        .header(InternalApiToken.HEADER, internalApiToken.value())
                        .param("followerId", "1")
                        .param("ids", "7", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value(8));
    }
}
