package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.config.SecurityConfig;
import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.adminservice.entity.ModerationActionType;
import com.tiktok.adminservice.entity.ReportTargetType;
import com.tiktok.adminservice.service.AdminService;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console's direct account actions and the filters of the audit log and the report ledger,
 * through the real security chain and argument binding. A filter that silently fails to bind
 * answers with the whole log, which reads as "nothing else happened".
 */
@WebMvcTest(controllers = {UserModerationController.class, ModerationActionController.class,
        ReportController.class})
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {"jwt.secret=" + ModerationControllersTest.JWT_SECRET,
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"})
class ModerationControllersTest {

    static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminService adminService;

    private final JwtProvider jwtProvider = new JwtProvider(JWT_SECRET);

    private String bearer(long userId, String role) {
        return "Bearer " + jwtProvider.generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS, "role", role), 60_000L);
    }

    @Test
    void warningAnAccountRecordsAWarningAgainstIt() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/42/warn")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Harassment in comments\"}"))
                .andExpect(status().isOk());

        verify(adminService).moderate(7L, ReportTargetType.USER, "42",
                ModerationActionType.WARN_USER, "Harassment in comments");
    }

    @Test
    void signingAnAccountOutRecordsARevocationAgainstIt() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/42/revoke-sessions")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Account taken over\"}"))
                .andExpect(status().isOk());

        verify(adminService).moderate(7L, ReportTargetType.USER, "42",
                ModerationActionType.REVOKE_SESSIONS, "Account taken over");
    }

    /** The reason is what the audit row exists to hold. */
    @Test
    void aWarningWithoutAReasonIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/42/warn")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminService);
    }

    @Test
    void anAccountWithoutTheAdminRoleCannotSignAnotherOut() throws Exception {
        mockMvc.perform(post("/api/v1/admin/users/42/revoke-sessions")
                        .header("Authorization", bearer(7L, "USER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"spam\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminService);
    }

    @Test
    void theAuditLogBindsEveryFilter() throws Exception {
        mockMvc.perform(get("/api/v1/admin/actions")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .param("adminId", "7")
                        .param("actionType", "BAN_USER")
                        .param("from", "2026-10-06T00:00:00Z")
                        .param("to", "2026-10-07T00:00:00Z"))
                .andExpect(status().isOk());

        verify(adminService).listActions(isNull(), isNull(), eq(7L), eq(ModerationActionType.BAN_USER),
                eq(Instant.parse("2026-10-06T00:00:00Z")), eq(Instant.parse("2026-10-07T00:00:00Z")),
                any(Pageable.class));
    }

    @Test
    void theLedgerBindsTheSearchAndTheDays() throws Exception {
        mockMvc.perform(get("/api/v1/admin/reports")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .param("q", "spam")
                        .param("from", "2026-10-06T00:00:00Z")
                        .param("to", "2026-10-07T00:00:00Z"))
                .andExpect(status().isOk());

        verify(adminService).listReports(isNull(), isNull(), isNull(), isNull(), eq("spam"),
                eq(Instant.parse("2026-10-06T00:00:00Z")), eq(Instant.parse("2026-10-07T00:00:00Z")),
                any(Pageable.class));
    }

    @Test
    void anUnknownDecisionTypeIsABadRequestRatherThanTheWholeLog() throws Exception {
        mockMvc.perform(get("/api/v1/admin/actions")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .param("actionType", "BANNED"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(adminService);
    }
}
