package com.tiktok.userservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.request.StoryAudienceScope;
import com.tiktok.userservice.dto.request.UpdateUserSettingsRequest;
import com.tiktok.userservice.dto.response.FriendshipResponse;
import com.tiktok.userservice.dto.response.RelationshipResponse;
import com.tiktok.userservice.dto.response.StoryAudienceResponse;
import com.tiktok.userservice.dto.response.UserSettingsPolicyResponse;
import com.tiktok.userservice.dto.response.UserSettingsResponse;
import com.tiktok.userservice.entity.SettingsAudience;
import com.tiktok.userservice.exception.TooManyProfileIdsException;
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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
            SettingsAudience.EVERYONE, SettingsAudience.EVERYONE, null, null, null, true, false,
            true, false, List.of(), true, true, true, true, true, true, true, null);

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
                LocalTime.of(22, 30), LocalTime.of(6, 30), false, false,
                false, true, List.of("spoiler"), true, false, true, true, true, true, false,
                "Asia/Ho_Chi_Minh"));

        mockMvc.perform(get("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commentAudience").value("FRIENDS"))
                .andExpect(jsonPath("$.data.dmAudience").value("NO_ONE"))
                .andExpect(jsonPath("$.data.screenTimeLimitMinutes").value(60))
                // Minute precision both ways, so a client can PATCH back what it read.
                .andExpect(jsonPath("$.data.sleepHoursStart").value("22:30"))
                .andExpect(jsonPath("$.data.sleepHoursEnd").value("06:30"))
                .andExpect(jsonPath("$.data.weeklyScreenTimeDigest").value(false))
                .andExpect(jsonPath("$.data.filterSpamOffensive").value(false))
                .andExpect(jsonPath("$.data.filterAllComments").value(true))
                .andExpect(jsonPath("$.data.commentKeywords[0]").value("spoiler"))
                // The names the web reads the switches by.
                .andExpect(jsonPath("$.data.pushLikes").value(true))
                .andExpect(jsonPath("$.data.pushComments").value(false))
                .andExpect(jsonPath("$.data.inAppReposts").value(false))
                .andExpect(jsonPath("$.data.timeZone").value("Asia/Ho_Chi_Minh"));
    }

    /**
     * The end is derived now, and a client from before that still sends it. Ignored rather than a
     * 400, so the old settings page keeps saving until it is reloaded.
     */
    @Test
    void patch_aStaleSleepHoursEndKey_isIgnored() throws Exception {
        when(userSettingsService.updateOwnSettings(eq(1L), any())).thenReturn(DEFAULTS);

        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sleepHoursStart": "22:00", "sleepHoursEnd": "12:00"}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateUserSettingsRequest> captor =
                ArgumentCaptor.forClass(UpdateUserSettingsRequest.class);
        verify(userSettingsService).updateOwnSettings(eq(1L), captor.capture());
        assertThat(captor.getValue().isSleepHoursStartSet()).isTrue();
        assertThat(captor.getValue().getSleepHoursStart()).isEqualTo(LocalTime.of(22, 0));
    }

    /** Sanitized in the setter, so the 30-character limit counts what would be stored. */
    @Test
    void patch_commentKeywords_areSanitizedBeforeTheyAreCounted() throws Exception {
        when(userSettingsService.updateOwnSettings(eq(1L), any())).thenReturn(DEFAULTS);

        mockMvc.perform(patch("/api/v1/users/me/settings")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"commentKeywords\": [\"  spo\u200biler \", \"giảm giá\"],"
                                + " \"filterAllComments\": true}"))
                .andExpect(status().isOk());

        ArgumentCaptor<UpdateUserSettingsRequest> captor =
                ArgumentCaptor.forClass(UpdateUserSettingsRequest.class);
        verify(userSettingsService).updateOwnSettings(eq(1L), captor.capture());
        assertThat(captor.getValue().getCommentKeywords()).containsExactly("spoiler", "giảm giá");
        assertThat(captor.getValue().isFilterAllCommentsSet()).isTrue();
        assertThat(captor.getValue().isFilterSpamOffensiveSet()).isFalse();
    }

    @Test
    void patch_commentKeywords_tooManyOrTooLongOrBlank_isAValidationError() throws Exception {
        String tooMany = java.util.stream.IntStream.range(0, 51).mapToObj(i -> "\"k" + i + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        for (String keywords : List.of(tooMany, "[\"" + "a".repeat(31) + "\"]", "[\"\\u200b\"]")) {
            mockMvc.perform(patch("/api/v1/users/me/settings")
                            .header("Authorization", "Bearer " + tokenFor(1L))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"commentKeywords\": " + keywords + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(userSettingsService);
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
                new UserSettingsPolicyResponse(7L, SettingsAudience.NO_ONE, SettingsAudience.FRIENDS,
                        false, true, List.of("spoiler"), true, false, true,
                        LocalTime.of(22, 0), LocalTime.of(6, 0), "Asia/Ho_Chi_Minh")));

        mockMvc.perform(get("/api/v1/users/internal/settings").param("ids", "7")
                        .header(InternalApiToken.HEADER, internalApiToken.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].userId").value(7))
                .andExpect(jsonPath("$.data[0].commentAudience").value("NO_ONE"))
                .andExpect(jsonPath("$.data[0].dmAudience").value("FRIENDS"))
                .andExpect(jsonPath("$.data[0].filterSpamOffensive").value(false))
                .andExpect(jsonPath("$.data[0].filterAllComments").value(true))
                .andExpect(jsonPath("$.data[0].commentKeywords[0]").value("spoiler"))
                // What notification-service gates a phone push on, by these names and in HH:mm.
                .andExpect(jsonPath("$.data[0].pushLikes").value(true))
                .andExpect(jsonPath("$.data[0].pushComments").value(false))
                .andExpect(jsonPath("$.data[0].pushNewFollowers").value(true))
                .andExpect(jsonPath("$.data[0].sleepHoursStart").value("22:00"))
                .andExpect(jsonPath("$.data[0].sleepHoursEnd").value("06:00"))
                .andExpect(jsonPath("$.data[0].timeZone").value("Asia/Ho_Chi_Minh"));
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
                SettingsAudience.EVERYONE, SettingsAudience.EVERYONE, null, null, null, true, true,
                true, false, List.of(), true, true, true, true, true, true, true, null));

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

    /** story-service asks for a page of owners at once, with the viewer's id and the ids as a list. */
    @Test
    void internalRelationships_answerWithTheWrappedListAndNoUserToken() throws Exception {
        when(followService.relationships(1L, List.of(2L, 3L))).thenReturn(List.of(
                new RelationshipResponse(2L, false, true, true, false),
                new RelationshipResponse(3L, true, false, false, false)));

        mockMvc.perform(get("/api/v1/users/internal/relationships")
                        .header(InternalApiToken.HEADER, internalApiToken.value())
                        .param("viewerId", "1")
                        .param("userIds", "2,3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].userId").value(2))
                .andExpect(jsonPath("$.data[0].blocked").value(false))
                .andExpect(jsonPath("$.data[0].privateAccount").value(true))
                .andExpect(jsonPath("$.data[0].following").value(true))
                .andExpect(jsonPath("$.data[0].friends").value(false))
                .andExpect(jsonPath("$.data[1].userId").value(3))
                .andExpect(jsonPath("$.data[1].blocked").value(true));
    }

    /** A guest reads stories too, so the viewer is optional; whether an account is private still comes back. */
    @Test
    void internalRelationships_withoutAViewer_isTheAnonymousQuestion() throws Exception {
        when(followService.relationships(null, List.of(2L))).thenReturn(List.of(
                new RelationshipResponse(2L, false, true, false, false)));

        mockMvc.perform(get("/api/v1/users/internal/relationships")
                        .header(InternalApiToken.HEADER, internalApiToken.value())
                        .param("userIds", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].privateAccount").value(true));
    }

    /** Who blocked whom is not for a user to ask: their own JWT does not stand in for the token. */
    @Test
    void internalRelationships_refuseACallerWithoutTheInternalToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/internal/relationships")
                        .param("viewerId", "1").param("userIds", "2"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/internal/relationships")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .param("viewerId", "1").param("userIds", "2"))
                .andExpect(status().isForbidden());

        verify(followService, never()).relationships(any(), any());
    }

    @Test
    void internalRelationships_moreThanAHundredIds_is400() throws Exception {
        when(followService.relationships(any(), any())).thenThrow(new TooManyProfileIdsException(100));
        String ids = IntStream.rangeClosed(1, 101).mapToObj(String::valueOf).collect(Collectors.joining(","));

        mockMvc.perform(get("/api/v1/users/internal/relationships")
                        .header(InternalApiToken.HEADER, internalApiToken.value())
                        .param("viewerId", "1")
                        .param("userIds", ids))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_PROFILE_IDS"));
    }

    /** notification-service pages through this: all four parameters in, the wrapped page out. */
    @Test
    void internalStoryAudience_answerWithTheWrappedPageAndNoUserToken() throws Exception {
        when(followService.storyAudience(7L, StoryAudienceScope.FRIENDS, 3L, 500))
                .thenReturn(new StoryAudienceResponse(List.of(11L, 12L), 12L));

        mockMvc.perform(get("/api/v1/users/internal/story-audience")
                        .header(InternalApiToken.HEADER, internalApiToken.value())
                        .param("userId", "7")
                        .param("scope", "FRIENDS")
                        .param("after", "3")
                        .param("size", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.userIds[0]").value(11))
                .andExpect(jsonPath("$.data.userIds[1]").value(12))
                .andExpect(jsonPath("$.data.next").value(12));
    }

    /**
     * The first page asks with no cursor and no size; the last has no cursor to give, and {@code next}
     * goes out as an explicit null rather than being left out. Pins today's shape, which
     * notification-service reads as "no more pages".
     */
    @Test
    void internalStoryAudience_firstAndLastPage_haveNoCursorAndCarryANullNext() throws Exception {
        when(followService.storyAudience(7L, StoryAudienceScope.FOLLOWERS, null, null))
                .thenReturn(new StoryAudienceResponse(List.of(13L), null));

        mockMvc.perform(get("/api/v1/users/internal/story-audience")
                        .header(InternalApiToken.HEADER, internalApiToken.value())
                        .param("userId", "7")
                        .param("scope", "FOLLOWERS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userIds[0]").value(13))
                .andExpect(jsonPath("$.data.next").value(nullValue()));
    }

    @Test
    void internalStoryAudience_unknownScope_is400() throws Exception {
        mockMvc.perform(get("/api/v1/users/internal/story-audience")
                        .header(InternalApiToken.HEADER, internalApiToken.value())
                        .param("userId", "7")
                        .param("scope", "EVERYONE"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(followService);
    }

    /** Who follows whom is not for a user to ask: their own JWT does not stand in for the token. */
    @Test
    void internalStoryAudience_refuseACallerWithoutTheInternalToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/internal/story-audience")
                        .param("userId", "7").param("scope", "FOLLOWERS"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/users/internal/story-audience")
                        .header("Authorization", "Bearer " + tokenFor(1L))
                        .param("userId", "7").param("scope", "FOLLOWERS"))
                .andExpect(status().isForbidden());

        verify(followService, never()).storyAudience(any(), any(), any(), any());
    }
}
