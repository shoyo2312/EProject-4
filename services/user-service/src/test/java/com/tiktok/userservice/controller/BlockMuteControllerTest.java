package com.tiktok.userservice.controller;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.response.BlockResponse;
import com.tiktok.userservice.dto.response.MuteResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.exception.AlreadyBlockedException;
import com.tiktok.userservice.exception.AlreadyMutedException;
import com.tiktok.userservice.exception.CannotBlockSelfException;
import com.tiktok.userservice.exception.CannotMuteSelfException;
import com.tiktok.userservice.exception.NotBlockedException;
import com.tiktok.userservice.exception.NotMutedException;
import com.tiktok.userservice.service.BlockService;
import com.tiktok.userservice.service.MuteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {BlockController.class, MuteController.class})
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class})
@TestPropertySource(properties = "jwt.secret=test-secret-at-least-32-bytes-long-0123456789")
class BlockMuteControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private BlockService blockService;

    @MockBean
    private MuteService muteService;

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
    void block_withValidToken_createsBlockAndReturns201() throws Exception {
        when(blockService.block(1L, 2L)).thenReturn(new BlockResponse(1L, 2L));

        mockMvc.perform(post("/api/v1/users/2/block")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.blockerId").value(1))
                .andExpect(jsonPath("$.data.blockedId").value(2));
    }

    @Test
    void block_self_returnsBadRequestFromDomainException() throws Exception {
        when(blockService.block(1L, 1L)).thenThrow(new CannotBlockSelfException());

        mockMvc.perform(post("/api/v1/users/1/block")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_BLOCK_SELF"));
    }

    @Test
    void block_alreadyBlocked_returnsConflict() throws Exception {
        when(blockService.block(1L, 2L)).thenThrow(new AlreadyBlockedException());

        mockMvc.perform(post("/api/v1/users/2/block")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_BLOCKED"));
    }

    @Test
    void block_withoutToken_isRejectedBeforeReachingService() throws Exception {
        mockMvc.perform(post("/api/v1/users/2/block"))
                .andExpect(status().isForbidden());

        verify(blockService, never()).block(any(), any());
    }

    @Test
    void unblock_notBlocked_returnsNotFound() throws Exception {
        doThrow(new NotBlockedException()).when(blockService).unblock(1L, 2L);

        mockMvc.perform(delete("/api/v1/users/2/block")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_BLOCKED"));
    }

    @Test
    void listBlocked_withValidToken_returnsPagedProfiles() throws Exception {
        UserProfileResponse blocked = new UserProfileResponse(9L, "eve", "Eve", null, null, 0, 0);
        Page<UserProfileResponse> page = new PageImpl<>(List.of(blocked), PageRequest.of(0, 10), 1);
        when(blockService.listBlocked(eq(1L), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/users/me/blocked")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value(9))
                .andExpect(jsonPath("$.data.page.totalElements").value(1));
    }

    @Test
    void mute_withValidToken_createsMuteAndReturns201() throws Exception {
        when(muteService.mute(1L, 2L)).thenReturn(new MuteResponse(1L, 2L));

        mockMvc.perform(post("/api/v1/users/2/mute")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.muterId").value(1))
                .andExpect(jsonPath("$.data.mutedId").value(2));
    }

    @Test
    void mute_self_returnsBadRequestFromDomainException() throws Exception {
        when(muteService.mute(1L, 1L)).thenThrow(new CannotMuteSelfException());

        mockMvc.perform(post("/api/v1/users/1/mute")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CANNOT_MUTE_SELF"));
    }

    @Test
    void mute_alreadyMuted_returnsConflict() throws Exception {
        when(muteService.mute(1L, 2L)).thenThrow(new AlreadyMutedException());

        mockMvc.perform(post("/api/v1/users/2/mute")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_MUTED"));
    }

    @Test
    void unmute_notMuted_returnsNotFound() throws Exception {
        doThrow(new NotMutedException()).when(muteService).unmute(1L, 2L);

        mockMvc.perform(delete("/api/v1/users/2/mute")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_MUTED"));
    }

    @Test
    void listMuted_withValidToken_returnsPagedProfiles() throws Exception {
        UserProfileResponse muted = new UserProfileResponse(11L, "frank", "Frank", null, null, 0, 0);
        Page<UserProfileResponse> page = new PageImpl<>(List.of(muted), PageRequest.of(0, 10), 1);
        when(muteService.listMuted(eq(1L), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/users/me/muted")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].userId").value(11));
    }
}
