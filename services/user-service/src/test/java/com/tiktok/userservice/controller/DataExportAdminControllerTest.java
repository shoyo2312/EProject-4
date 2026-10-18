package com.tiktok.userservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.PageableConfig;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.response.DataExportAdminSummaryResponse;
import com.tiktok.userservice.service.DataExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The summary names who asked for their data, so it is the console's alone. */
@WebMvcTest(controllers = DataExportAdminController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class,
        PageableConfig.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef",
        "app.media.allowed-hosts=cdn.example.com"
})
class DataExportAdminControllerTest {

    private static final String SUMMARY = "/api/v1/users/admin/data-exports/summary";

    private final JwtProvider jwtProvider = new JwtProvider("test-secret-at-least-32-bytes-long-0123456789");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataExportService dataExportService;

    @Test
    void summary_answersAnAdmin_andRefusesEveryoneElse() throws Exception {
        when(dataExportService.adminSummary()).thenReturn(new DataExportAdminSummaryResponse(
                2, Instant.parse("2026-10-18T10:00:00Z"), 1, 5, Instant.parse("2026-10-11T10:00:00Z"), 0,
                List.of()));

        mockMvc.perform(get(SUMMARY).header("Authorization", "Bearer " + token(Map.of("role", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.processing").value(2))
                .andExpect(jsonPath("$.data.stalled").value(1));
        mockMvc.perform(get(SUMMARY).header("Authorization", "Bearer " + token(Map.of())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(SUMMARY))
                .andExpect(status().isForbidden());

        verify(dataExportService, times(1)).adminSummary();
    }

    private String token(Map<String, Object> claims) {
        Map<String, Object> all = new HashMap<>(claims);
        all.put(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS);
        return jwtProvider.generateToken("1", all, 60_000L);
    }
}
