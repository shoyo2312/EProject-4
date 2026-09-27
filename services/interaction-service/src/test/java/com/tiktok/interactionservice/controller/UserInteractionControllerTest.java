package com.tiktok.interactionservice.controller;

import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.interactionservice.AbstractInteractionServiceIT;
import com.tiktok.interactionservice.dto.response.VideoIdPageResponse;
import com.tiktok.interactionservice.exception.PrivateAccountException;
import com.tiktok.interactionservice.service.RepostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class UserInteractionControllerTest extends AbstractInteractionServiceIT {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RepostService repostService;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private String tokenFor(long userId) {
        return new JwtProvider(jwtSecret).generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    /** The token is how a follower is told from a stranger, so the caller has to reach the service. */
    @Test
    void reposts_passTheCallerWhenThereIsOne() throws Exception {
        when(repostService.listReposts(any(), eq(7L), any(), anyInt()))
                .thenReturn(new VideoIdPageResponse(List.of(), null, false));

        mockMvc.perform(get("/api/v1/interactions/users/7/reposts")
                        .header("Authorization", "Bearer " + tokenFor(5L)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/interactions/users/7/reposts"))
                .andExpect(status().isOk());

        verify(repostService).listReposts(5L, 7L, null, 20);
        verify(repostService).listReposts(null, 7L, null, 20);
    }

    @Test
    void reposts_ofAPrivateAccount_are403ToAStranger() throws Exception {
        when(repostService.listReposts(any(), eq(7L), any(), anyInt())).thenThrow(new PrivateAccountException());

        mockMvc.perform(get("/api/v1/interactions/users/7/reposts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PRIVATE_ACCOUNT"));
    }
}
