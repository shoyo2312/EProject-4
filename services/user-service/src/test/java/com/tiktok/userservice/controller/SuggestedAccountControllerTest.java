package com.tiktok.userservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.PageableConfig;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.response.SuggestedAccountResponse;
import com.tiktok.userservice.dto.response.UserProfileResponse;
import com.tiktok.userservice.service.SuggestedAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SuggestedAccountController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class,
        PageableConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"
})
class SuggestedAccountControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SuggestedAccountService suggestedAccountService;

    private String tokenFor(long userId) {
        return new JwtProvider(JWT_SECRET).generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    @Test
    void suggestedAccounts_withoutToken_isRejectedBeforeReachingService() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/suggested-accounts"))
                .andExpect(status().isForbidden());

        verify(suggestedAccountService, never()).suggest(any(), any());
    }

    @Test
    void suggestedAccounts_areTheCallersOwn() throws Exception {
        UserProfileResponse ann = new UserProfileResponse(13L, "ann", "Ann", null, null, 0, 0, false, null);
        when(suggestedAccountService.suggest(1L, null)).thenReturn(List.of(
                new SuggestedAccountResponse(ann, SuggestedAccountResponse.Reason.FOLLOWED_BY, "user11")));

        mockMvc.perform(get("/api/v1/users/me/suggested-accounts")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].profile.userId").value(13))
                .andExpect(jsonPath("$.data[0].reason").value("FOLLOWED_BY"))
                .andExpect(jsonPath("$.data[0].followedBy").value("user11"));
    }

    @Test
    void suggestedAccounts_passTheLimitThrough() throws Exception {
        when(suggestedAccountService.suggest(1L, 5)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users/me/suggested-accounts?limit=5")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk());

        verify(suggestedAccountService).suggest(1L, 5);
    }
}
