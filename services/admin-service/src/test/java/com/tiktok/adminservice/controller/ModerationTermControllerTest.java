package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.config.SecurityConfig;
import com.tiktok.adminservice.dto.request.AddModerationTermRequest;
import com.tiktok.adminservice.dto.request.TextPreviewRequest;
import com.tiktok.adminservice.dto.response.ModerationTermResponse;
import com.tiktok.adminservice.dto.response.ModerationTermSnapshotResponse;
import com.tiktok.adminservice.dto.response.TextPreviewResponse;
import com.tiktok.adminservice.service.ModerationTermService;
import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.common.http.InternalApiToken;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.moderation.Category;
import com.tiktok.moderation.Decision;
import com.tiktok.moderation.Flag;
import com.tiktok.moderation.Surface;
import com.tiktok.moderation.rules.AdminTerm;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The word list's endpoints through the real security chain: an admin's token for the console, the
 * internal token — and nothing else — for the services that poll it.
 */
@WebMvcTest(controllers = {ModerationTermController.class, InternalModerationTermController.class})
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {"jwt.secret=" + ModerationTermControllerTest.JWT_SECRET,
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"})
class ModerationTermControllerTest {

    static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InternalApiToken internalApiToken;

    @MockitoBean
    private ModerationTermService moderationTermService;

    private final JwtProvider jwtProvider = new JwtProvider(JWT_SECRET);

    private String bearer(long userId, String role) {
        return "Bearer " + jwtProvider.generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS, "role", role), 60_000L);
    }

    @Test
    void anAdminAddsATerm_sanitizedBeforeItIsStored() throws Exception {
        when(moderationTermService.add(eq(7L), any())).thenReturn(new ModerationTermResponse(1L, "cút đi",
                Category.INSULT, AdminTerm.Action.HOLD, null, 7L, Instant.now()));

        mockMvc.perform(post("/api/v1/admin/moderation/terms")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"term\":\"  cút​ đi \",\"category\":\"INSULT\",\"action\":\"HOLD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.term").value("cút đi"));

        ArgumentCaptor<AddModerationTermRequest> request = ArgumentCaptor.forClass(AddModerationTermRequest.class);
        verify(moderationTermService).add(eq(7L), request.capture());
        assertThat(request.getValue().term()).isEqualTo("cút đi");
    }

    @Test
    void aTermWithoutAnAction_isRefused() throws Exception {
        mockMvc.perform(post("/api/v1/admin/moderation/terms")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"term\":\"cút đi\",\"category\":\"INSULT\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(moderationTermService);
    }

    /** Only invisible characters is blank once sanitized — it would never match anything. */
    @Test
    void anInvisibleTerm_isRefused() throws Exception {
        mockMvc.perform(post("/api/v1/admin/moderation/terms")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"term\":\"​​\",\"category\":\"INSULT\",\"action\":\"HOLD\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(moderationTermService);
    }

    @Test
    void aUserWhoIsNotAnAdmin_cannotTouchTheList() throws Exception {
        mockMvc.perform(get("/api/v1/admin/moderation/terms").header("Authorization", bearer(8L, "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/admin/moderation/terms/1").header("Authorization", bearer(8L, "USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(moderationTermService);
    }

    @Test
    void anAdminRemovesATerm() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/moderation/terms/5").header("Authorization", bearer(7L, "ADMIN")))
                .andExpect(status().isOk());

        verify(moderationTermService).delete(7L, 5L);
    }

    @Test
    void thePreview_defaultsToAComment_andShowsWhoRaisedEachFlag() throws Exception {
        when(moderationTermService.preview(any())).thenReturn(new TextPreviewResponse(Decision.HOLD,
                List.of(new Flag(Category.HATE, Flag.HOLD, "model:HATE:0.97", Flag.Source.MODEL)),
                "rules-x+admin-3+hsd-v1", true));

        mockMvc.perform(post("/api/v1/admin/moderation/text-preview")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"bọn này cút về quê\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("HOLD"))
                .andExpect(jsonPath("$.data.flags[0].source").value("MODEL"))
                .andExpect(jsonPath("$.data.classifierAnswered").value(true));

        ArgumentCaptor<TextPreviewRequest> request = ArgumentCaptor.forClass(TextPreviewRequest.class);
        verify(moderationTermService).preview(request.capture());
        assertThat(request.getValue().surface()).isEqualTo(Surface.COMMENT);
    }

    @Test
    void theServices_readTheListWithTheInternalToken() throws Exception {
        when(moderationTermService.snapshot()).thenReturn(new ModerationTermSnapshotResponse(3, List.of(
                new ModerationTermSnapshotResponse.Term("cút đi", Category.INSULT, AdminTerm.Action.HOLD))));

        mockMvc.perform(get("/api/v1/admin/internal/blocklist").header(InternalApiToken.HEADER, internalApiToken.value()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.terms[0].action").value("HOLD"));
    }

    /** A port reached without the gateway, or an admin's own token, is still not an internal caller. */
    @Test
    void theInternalList_refusesEveryoneElse() throws Exception {
        mockMvc.perform(get("/api/v1/admin/internal/blocklist"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/internal/blocklist").header("Authorization", bearer(7L, "ADMIN")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(moderationTermService);
    }
}
