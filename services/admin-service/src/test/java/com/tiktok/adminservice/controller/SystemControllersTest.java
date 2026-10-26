package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.client.ModerationSettingsClient;
import com.tiktok.adminservice.config.SecurityConfig;
import com.tiktok.adminservice.service.SystemStatusService;
import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What the System screens read about the other services: admins only, like the rest of the console. */
@WebMvcTest(controllers = {SystemController.class, SettingsController.class})
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {"jwt.secret=" + SystemControllersTest.JWT_SECRET,
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"})
class SystemControllersTest {

    static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemStatusService systemStatusService;

    @MockitoBean
    private ModerationSettingsClient moderationSettingsClient;

    private final JwtProvider jwtProvider = new JwtProvider(JWT_SECRET);

    private String bearer(String role) {
        return "Bearer " + jwtProvider.generateToken("7",
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS, "role", role), 60_000L);
    }

    @Test
    void anAdminReadsTheOutboxesAndTheTextModerationInForce() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system/outboxes").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/admin/settings/text-moderation").header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk());

        verify(systemStatusService).outboxes();
        verify(systemStatusService).textModeration();
    }

    @Test
    void anyoneElseIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system/outboxes").header("Authorization", bearer("USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/settings/text-moderation"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(systemStatusService);
    }
}
