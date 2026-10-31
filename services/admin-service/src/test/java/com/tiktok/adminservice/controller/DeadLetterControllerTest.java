package com.tiktok.adminservice.controller;

import com.tiktok.adminservice.config.SecurityConfig;
import com.tiktok.adminservice.service.DeadLetterService;
import com.tiktok.common.config.InternalApiAutoConfiguration;
import com.tiktok.crypto.jwt.JwtProvider;
import com.tiktok.security.jwt.JwtSecurityAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DeadLetterController.class)
@Import({SecurityConfig.class, JwtSecurityAutoConfiguration.class, InternalApiAutoConfiguration.class})
@TestPropertySource(properties = {"jwt.secret=" + DeadLetterControllerTest.JWT_SECRET,
        "INTERNAL_API_TOKEN=internal-token-for-tests-0123456789abcdef"})
class DeadLetterControllerTest {

    static final String JWT_SECRET = "test-secret-at-least-32-bytes-long-0123456789";
    private static final String TOPIC = "video.video-events.DLT";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeadLetterService deadLetterService;

    private final JwtProvider jwtProvider = new JwtProvider(JWT_SECRET);

    private String bearer(long userId, String role) {
        return "Bearer " + jwtProvider.generateToken(String.valueOf(userId),
                Map.of(JwtProvider.CLAIM_TOKEN_TYPE, JwtProvider.TOKEN_TYPE_ACCESS, "role", role), 60_000L);
    }

    /** A topic name is dots all the way through; none of it may be taken for an extension. */
    @Test
    void aTopicNameReachesTheServiceWhole() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system/dead-letters/" + TOPIC)
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .param("limit", "20"))
                .andExpect(status().isOk());

        verify(deadLetterService).listRecords(TOPIC, 20);
    }

    @Test
    void aForcedReplayCarriesTheAdminAndTheCoordinates() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/dead-letters/" + TOPIC + "/replay")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partition\":0,\"offset\":42,\"force\":true}"))
                .andExpect(status().isOk());

        verify(deadLetterService).replay(7L, TOPIC, 0, 42L, true);
    }

    @Test
    void replayingEveryPendingRecordCarriesTheAdmin() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/dead-letters/" + TOPIC + "/replay-pending")
                        .header("Authorization", bearer(7L, "ADMIN")))
                .andExpect(status().isOk());

        verify(deadLetterService).replayPending(7L, TOPIC);
    }

    @Test
    void aReplayWithoutAnOffsetIsRefused() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/dead-letters/" + TOPIC + "/replay")
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"partition\":0}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deadLetterService);
    }

    @Test
    void aListingIsCapped() throws Exception {
        mockMvc.perform(get("/api/v1/admin/system/dead-letters/" + TOPIC)
                        .header("Authorization", bearer(7L, "ADMIN"))
                        .param("limit", "500"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(deadLetterService);
    }

    @Test
    void anAccountWithoutTheAdminRoleCannotReplay() throws Exception {
        mockMvc.perform(post("/api/v1/admin/system/dead-letters/" + TOPIC + "/replay-pending")
                        .header("Authorization", bearer(7L, "USER")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(deadLetterService);
    }
}
