package com.tiktok.userservice.controller;

import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import com.tiktok.userservice.config.SecurityConfig;
import com.tiktok.userservice.dto.response.ScreenTimeDayResponse;
import com.tiktok.userservice.dto.response.ScreenTimeWeekResponse;
import com.tiktok.userservice.exception.TooManyPasscodeAttemptsException;
import com.tiktok.userservice.service.ScreenTimeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ScreenTimeController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {
        "jwt.secret=test-secret-at-least-32-bytes-long-0123456789",
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"
})
class ScreenTimeControllerTest {

    private static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ScreenTimeService screenTimeService;

    private String bearer() {
        return "Bearer " + new JwtProvider(JWT_SECRET).generateToken("1",
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS), 60_000L);
    }

    @Test
    void heartbeat_reachesTheServiceForTheCaller() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/screen-time/heartbeat")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seconds\": 60, \"timeZone\": \"Asia/Ho_Chi_Minh\"}"))
                .andExpect(status().isOk());

        verify(screenTimeService).recordHeartbeat(eq(1L), eq(60), eq("Asia/Ho_Chi_Minh"), any(Instant.class));
    }

    @Test
    void heartbeat_withoutSecondsOrZone_isAValidationError() throws Exception {
        for (String body : List.of("{\"timeZone\": \"Asia/Ho_Chi_Minh\"}", "{\"seconds\": 0, \"timeZone\": \"Asia/Ho_Chi_Minh\"}",
                "{\"seconds\": 60}", "{\"seconds\": 60, \"timeZone\": \" \"}")) {
            mockMvc.perform(post("/api/v1/users/me/screen-time/heartbeat")
                            .header("Authorization", bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(screenTimeService);
    }

    @Test
    void heartbeat_withoutAToken_isRefused() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/screen-time/heartbeat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seconds\": 60, \"timeZone\": \"Asia/Ho_Chi_Minh\"}"))
                .andExpect(status().is4xxClientError());

        verifyNoInteractions(screenTimeService);
    }

    @Test
    void verify_answersWhetherThePasscodeWasRight() throws Exception {
        when(screenTimeService.verifyPasscode(eq(1L), eq("1234"), any(Instant.class))).thenReturn(true);

        mockMvc.perform(post("/api/v1/users/me/screen-time/passcode/verify")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passcode\": \"1234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.correct").value(true));
    }

    @Test
    void verify_whileLocked_is429WithRetryAfter() throws Exception {
        when(screenTimeService.verifyPasscode(eq(1L), eq("1234"), any(Instant.class)))
                .thenThrow(new TooManyPasscodeAttemptsException(Duration.ofMinutes(14)));

        mockMvc.perform(post("/api/v1/users/me/screen-time/passcode/verify")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passcode\": \"1234\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "840"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_PASSCODE_ATTEMPTS"));
    }

    /** Not four digits can never match, and is not counted as a guess. */
    @Test
    void verify_notFourDigits_isAValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/users/me/screen-time/passcode/verify")
                        .header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"passcode\": \"12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(screenTimeService);
    }

    /** The shape the web charts: ISO dates, seconds split by day and night. */
    @Test
    void week_answersTheDaysByDate() throws Exception {
        when(screenTimeService.getWeek(1L, LocalDate.parse("2026-10-19"))).thenReturn(new ScreenTimeWeekResponse(
                LocalDate.parse("2026-10-19"),
                List.of(new ScreenTimeDayResponse(LocalDate.parse("2026-10-19"), 600, 120))));

        mockMvc.perform(get("/api/v1/users/me/screen-time").param("weekStart", "2026-10-19")
                        .header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weekStart").value("2026-10-19"))
                .andExpect(jsonPath("$.data.days[0].date").value("2026-10-19"))
                .andExpect(jsonPath("$.data.days[0].daySeconds").value(600))
                .andExpect(jsonPath("$.data.days[0].nightSeconds").value(120));
    }
}
