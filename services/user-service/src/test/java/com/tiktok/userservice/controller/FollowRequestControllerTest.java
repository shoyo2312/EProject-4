package com.tiktok.userservice.controller;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.PageableConfig;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.response.FollowResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.exception.AlreadyRequestedException;
import com.tiktok.userservice.exception.FollowRequestNotFoundException;
import com.tiktok.userservice.service.FollowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {FollowRequestController.class, FollowController.class})
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, PageableConfig.class})
@TestPropertySource(properties = "jwt.secret=test-secret-at-least-32-bytes-long-0123456789")
class FollowRequestControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FollowService followService;

    private String tokenFor(long userId) {
        return new JwtProvider(JWT_SECRET).generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    /** 201 either way; the status is how the client tells Following from Requested. */
    @Test
    void follow_ofAPrivateAccount_is201Requested() throws Exception {
        when(followService.follow(1L, 2L))
                .thenReturn(new FollowResponse(1L, 2L, FollowResponse.Status.REQUESTED));

        mockMvc.perform(post("/api/v1/users/2/follow").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

    @Test
    void follow_withARequestPending_is409AlreadyRequested() throws Exception {
        when(followService.follow(1L, 2L)).thenThrow(new AlreadyRequestedException());

        mockMvc.perform(post("/api/v1/users/2/follow").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_REQUESTED"));
    }

    @Test
    void list_isTheCallersOwnRequests() throws Exception {
        UserProfileResponse requester = new UserProfileResponse(7L, "carol", "Carol", null, null, 0, 0, false);
        when(followService.listFollowRequests(eq(1L), any()))
                .thenReturn(new PageImpl<>(List.of(requester), PageRequest.of(0, 20), 1));

        mockMvc.perform(get("/api/v1/users/me/follow-requests").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value(7));
    }

    @Test
    void approve_answersTheNewFollow() throws Exception {
        when(followService.approveFollowRequest(1L, 7L))
                .thenReturn(new FollowResponse(7L, 1L, FollowResponse.Status.FOLLOWING));

        mockMvc.perform(post("/api/v1/users/me/follow-requests/7/approve")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followerId").value(7))
                .andExpect(jsonPath("$.data.status").value("FOLLOWING"));
    }

    @Test
    void approve_withNothingPending_is404() throws Exception {
        when(followService.approveFollowRequest(1L, 7L)).thenThrow(new FollowRequestNotFoundException());

        mockMvc.perform(post("/api/v1/users/me/follow-requests/7/approve")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FOLLOW_REQUEST_NOT_FOUND"));
    }

    @Test
    void reject_actsOnTheCallersOwnRequest() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me/follow-requests/7")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk());

        verify(followService).rejectFollowRequest(1L, 7L);
    }
}
